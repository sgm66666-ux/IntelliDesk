package com.intellidesk.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.document.DocumentMapper;
import com.intellidesk.document.DocumentChunk;
import com.intellidesk.document.DocumentChunkMapper;
import com.intellidesk.document.DocumentIndexTask;
import com.intellidesk.document.DocumentIndexTaskMapper;
import com.intellidesk.document.DocumentService;
import com.intellidesk.document.KnowledgeDocument;
import com.intellidesk.document.dto.DocumentUploadResponse;
import com.intellidesk.document.model.DocumentStatus;
import com.intellidesk.evaluation.EvalHashing;
import com.intellidesk.infrastructure.config.MinioProperties;
import com.intellidesk.infrastructure.config.EmbeddingProperties;
import com.intellidesk.infrastructure.config.RabbitMqProperties;
import com.intellidesk.infrastructure.config.RetrievalRabbitMqProperties;
import com.intellidesk.knowledge.KnowledgeBase;
import com.intellidesk.knowledge.KnowledgeBaseService;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import com.intellidesk.workspace.Workspace;
import com.intellidesk.workspace.WorkspaceService;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import com.intellidesk.document.model.DocumentFormat;
import com.intellidesk.document.parser.DocumentFormatDetector;
import com.intellidesk.document.parser.ParserRegistry;
import com.intellidesk.retrieval.task.DocumentRetrievalTask;
import com.intellidesk.retrieval.task.DocumentRetrievalTaskMapper;
import com.intellidesk.retrieval.task.RetrievalTaskStatus;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 8 Wave 2 — B-class end-to-end document processing benchmark harness.
 *
 * <p>Exercises the real document ingestion pipeline:
 * backend → PostgreSQL → MinIO → RabbitMQ → consumer → parser → chunk →
 * COMPLETED state (with durable handoff to embedding/index).
 *
 * <p>No fake branch, no direct DB READY mutation, no RabbitMQ bypass.
 * Latency is measured from the start of {@link DocumentService#uploadDocument}
 * until the document reaches {@link DocumentStatus#COMPLETED}.
 *
 * <p>Smoke tests run under the {@code test} + {@code bench} profiles and are
 * marked {@code NOT_BENCHMARK_EVIDENCE} in raw metadata.
 */
@SpringBootTest
@ActiveProfiles(resolver = DocumentBenchmarkProfilesResolver.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(TestInfrastructureConfig.class)
public class DocumentProcessingBenchmarkHarness {

    public static final String SCENARIO = "document-processing";
    public static final String MODE = "e2e-real";
    public static final String DEFAULT_RUN_SET_ID = "document-processing-bench-001";
    public static final String RUN_SET_ID_PROPERTY = "documentProcessingBenchRunSetId";

    // Approved parameters from Phase 8 Wave 2 plan v2.2 §1.3.
    public static final int WARMUP_SAMPLES = 0;
    public static final int EXECUTIONS_PER_RUN = 20;
    public static final int INDEPENDENT_RUNS = 3;
    public static final long TIMEOUT_MS = 120_000L;
    public static final long MAX_RUN_DURATION_MS = 45 * 60 * 1000L;
    public static final int CONCURRENCY = 1;
    public static final String PERCENTILE_METHOD = "nearest-rank";
    public static final String PROVIDER_MODE = "real";

    public static final class Config {
        private final int warmupSamples;
        private final int executionsPerRun;
        private final int runs;
        private final long timeoutMs;
        private final long maxRunDurationMs;
        private final int concurrency;
        private final String runSetId;

        public Config(int warmupSamples, int executionsPerRun, int runs,
                      long timeoutMs, long maxRunDurationMs, int concurrency,
                      String runSetId) {
            this.warmupSamples = warmupSamples;
            this.executionsPerRun = executionsPerRun;
            this.runs = runs;
            this.timeoutMs = timeoutMs;
            this.maxRunDurationMs = maxRunDurationMs;
            this.concurrency = concurrency;
            this.runSetId = runSetId;
        }

        public static Config canonical() {
            return new Config(WARMUP_SAMPLES, EXECUTIONS_PER_RUN, INDEPENDENT_RUNS,
                    TIMEOUT_MS, MAX_RUN_DURATION_MS, CONCURRENCY,
                    DEFAULT_RUN_SET_ID + "-" + Instant.now().toEpochMilli());
        }

        public static Config smoke() {
            return new Config(0, 1, 1, 60_000L, 120_000L, 1,
                    "document-processing-smoke-" + UUID.randomUUID());
        }

        public Config withExecutions(int executionsPerRun) {
            return new Config(warmupSamples, executionsPerRun, runs, timeoutMs,
                    maxRunDurationMs, concurrency, runSetId);
        }

        public Config withRunSetId(String runSetId) {
            return new Config(warmupSamples, executionsPerRun, runs, timeoutMs,
                    maxRunDurationMs, concurrency, runSetId);
        }
    }

    public static final class ExecutionResult {
        private final long startNs;
        private final long endNs;
        private final boolean success;
        private final String error;
        private final Long documentId;

        public ExecutionResult(long startNs, long endNs, boolean success,
                               String error, Long documentId) {
            this.startNs = startNs;
            this.endNs = endNs;
            this.success = success;
            this.error = error;
            this.documentId = documentId;
        }
    }

    public static final class RunResult {
        private final List<Double> latencies;
        private final int successes;
        private final int failures;

        public RunResult(List<Double> latencies, int successes, int failures) {
            this.latencies = latencies;
            this.successes = successes;
            this.failures = failures;
        }
    }

    public static final class Summary {
        private final Path runSetDir;
        private final String configHash;
        private final List<RunResult> runs;
        private final Map<BenchmarkPercentileCalculator.Percentile, Double> percentiles;

        public Summary(Path runSetDir, String configHash, List<RunResult> runs,
                       Map<BenchmarkPercentileCalculator.Percentile, Double> percentiles) {
            this.runSetDir = runSetDir;
            this.configHash = configHash;
            this.runs = runs;
            this.percentiles = percentiles;
        }
    }

    private static final ObjectMapper OM = new ObjectMapper();
    private static final byte[] SAMPLE_DOC_BYTES =
            ("IntelliDesk document processing benchmark sample.\n"
                    + "This text is intentionally small so that parse and chunk phases remain fast.\n"
                    + "Line three.\nLine four.\nLine five.").getBytes(StandardCharsets.UTF_8);

    @Autowired
    private DocumentService documentService;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private UserService userService;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private DocumentChunkMapper documentChunkMapper;

    @Autowired
    private DocumentIndexTaskMapper documentIndexTaskMapper;

    @Autowired
    private DocumentRetrievalTaskMapper documentRetrievalTaskMapper;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private MinioProperties minioProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    private RabbitMqProperties rabbitMqProperties;

    @Autowired
    private RetrievalRabbitMqProperties retrievalRabbitMqProperties;

    @Autowired
    private EmbeddingProperties embeddingProperties;

    @Autowired
    private DocumentFormatDetector documentFormatDetector;

    @Autowired
    private ParserRegistry parserRegistry;

    @TempDir
    private Path tempDir;

    private User benchUser;
    private Workspace benchWorkspace;
    private KnowledgeBase benchKb;
    private final List<Long> uploadedDocumentIds = new ArrayList<>();
    private final List<Map<String, Object>> runtimeDocumentFacts = new ArrayList<>();

    @BeforeAll
    void provision() {
        benchUser = userService.register(
                "bench-document-user-" + UUID.randomUUID(),
                "bench-password-" + UUID.randomUUID(),
                "bench-" + UUID.randomUUID() + "@example.com",
                "Bench Document User");

        benchWorkspace = workspaceService.createWorkspace(
                "bench-document-workspace-" + UUID.randomUUID(),
                "Document processing benchmark workspace",
                benchUser.getId());

        benchKb = knowledgeBaseService.createKnowledgeBase(
                benchWorkspace.getId(), benchUser.getId(),
                "bench-document-kb-" + UUID.randomUUID(),
                "Document processing benchmark KB",
                "RECURSIVE", 512, 64);

        ensureMinioBucketExists();
    }

    private void ensureMinioBucketExists() {
        try {
            String bucket = minioProperties.getBucket();
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ensure MinIO bucket exists", e);
        }
    }

    @AfterAll
    void cleanup() {
        cleanupProvisionedResources();
    }

    private boolean cleanupProvisionedResources() {
        boolean success = true;
        // Delete all documents belonging to the benchmark KB, including any that
        // were partially created but not recorded in uploadedDocumentIds.
        if (benchKb != null && benchKb.getId() != null) {
            try {
                List<KnowledgeDocument> docs = documentMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeDocument>()
                                .eq(KnowledgeDocument::getKnowledgeBaseId, benchKb.getId()));
                for (KnowledgeDocument doc : docs) {
                    try {
                        documentService.deleteDocument(
                                benchWorkspace.getId(), benchKb.getId(), doc.getId(), benchUser.getId());
                    } catch (Exception e) {
                        success = false;
                    }
                }
            } catch (Exception e) {
                success = false;
            }
        }

        if (benchKb != null && benchKb.getId() != null) {
            try {
                knowledgeBaseService.deleteKnowledgeBase(
                        benchWorkspace.getId(), benchKb.getId(), benchUser.getId());
            } catch (Exception e) {
                success = false;
            }
            benchKb = null;
        }
        if (benchWorkspace != null && benchWorkspace.getId() != null) {
            try {
                workspaceService.deleteWorkspace(benchWorkspace.getId(), benchUser.getId());
            } catch (Exception e) {
                success = false;
            }
            benchWorkspace = null;
        }
        if (benchUser != null && benchUser.getId() != null) {
            try {
                jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", benchUser.getId());
                success &= userService.removeById(benchUser.getId());
            } catch (Exception error) {
                success = false;
            }
            benchUser = null;
        }
        return success;
    }

    public static Path resolveBenchRoot() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path projectRoot = cwd.getFileName().toString().equals("backend") ? cwd.getParent() : cwd;
        return projectRoot.resolve("docs").resolve("evaluation").resolve("bench");
    }

    private Map<String, Object> buildHarnessConfig(Config config) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("schema_version", "1.0");
        cfg.put("scenario", SCENARIO);
        cfg.put("mode", MODE);
        cfg.put("target", "DocumentService.uploadDocument");
        cfg.put("provider_mode", PROVIDER_MODE);
        cfg.put("warmup_samples", config.warmupSamples);
        cfg.put("executions_per_run", config.executionsPerRun);
        cfg.put("runs", config.runs);
        cfg.put("timeout_ms", config.timeoutMs);
        cfg.put("max_run_duration_ms", config.maxRunDurationMs);
        cfg.put("concurrency", config.concurrency);
        cfg.put("independent_runs", INDEPENDENT_RUNS);
        cfg.put("percentile_method", PERCENTILE_METHOD);
        cfg.put("success_semantics",
                "DocumentStatus.COMPLETED plus RetrievalTaskStatus.READY via real parser/embedding/index pipeline");
        return cfg;
    }

    private String computeConfigHash(Config config) {
        return BenchmarkRunSetManager.canonicalHash(buildHarnessConfig(config));
    }

    private Map<String, Object> buildEnvironmentIdentity() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("os_name", System.getProperty("os.name", "unknown"));
        env.put("os_arch", System.getProperty("os.arch", "unknown"));
        env.put("java_version", System.getProperty("java.version", "unknown"));
        env.put("java_vm_name", System.getProperty("java.vm.name", "unknown"));
        env.put("available_processors", Runtime.getRuntime().availableProcessors());
        env.put("max_memory_bytes", Runtime.getRuntime().maxMemory());
        return env;
    }

    private String computeEnvironmentHash() {
        return BenchmarkRunSetManager.canonicalHash(buildEnvironmentIdentity());
    }

    public Summary execute(Path benchRoot, Config config) throws Exception {
        return execute(benchRoot, config, BenchmarkRunSetManager.EvidencePurpose.SMOKE);
    }

    private Summary execute(Path benchRoot, Config config,
                            BenchmarkRunSetManager.EvidencePurpose evidencePurpose) throws Exception {
        Map<String, Object> performanceConfig = buildHarnessConfig(config);
        Map<String, Object> environmentIdentity = buildEnvironmentIdentity();
        String configHash = BenchmarkRunSetManager.canonicalHash(performanceConfig);
        String environmentHash = BenchmarkRunSetManager.canonicalHash(environmentIdentity);

        Path runSetDir = BenchmarkRunSetManager.createJavaRunSet(
                benchRoot, SCENARIO, config.runSetId,
                BenchmarkRunSetManager.ArtifactContract.B_CLASS, evidencePurpose,
                performanceConfig, environmentIdentity, List.of(MODE), config.runs);
        BenchmarkRunSetManager.beginMeasurement(runSetDir);
        runtimeDocumentFacts.clear();
        Map<String, Object> preMeasurement = captureServiceProvenanceSnapshot("pre-measurement");

        List<RunResult> results = new ArrayList<>();
        try {
            for (int i = 1; i <= config.runs; i++) {
                RunResult result = runOneRun("run-" + i, runSetDir, config,
                        configHash, environmentHash, evidencePurpose);
                results.add(result);
            }
            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("captured_at", Instant.now().toString());
            provenance.put("pre_measurement", preMeasurement);
            provenance.put("measured_document_ids", runtimeDocumentFacts.stream()
                    .map(fact -> fact.get("document_id")).toList());
            provenance.put("documents", new ArrayList<>(runtimeDocumentFacts));
            provenance.put("final_snapshot", captureServiceProvenanceSnapshot("pre-finalization"));
            BenchmarkRunSetManager.recordRuntimeProvenance(runSetDir, provenance);
        } catch (Exception e) {
            BenchmarkRunSetManager.updateStatus(runSetDir, BenchmarkRunSetManager.Status.FAILED,
                    e.getMessage());
            throw e;
        }

        List<Double> allLatencies = new ArrayList<>();
        for (RunResult r : results) {
            allLatencies.addAll(r.latencies);
        }
        Map<BenchmarkPercentileCalculator.Percentile, Double> percentiles =
                BenchmarkPercentileCalculator.compute(allLatencies);
        return new Summary(runSetDir, configHash, results, percentiles);
    }

    private RunResult runOneRun(String runId, Path runSetDir, Config config,
                                String configHash, String environmentHash,
                                BenchmarkRunSetManager.EvidencePurpose evidencePurpose) throws Exception {
        BenchmarkRawWriter writer = new BenchmarkRawWriter(
                runSetDir, SCENARIO, MODE, runId, configHash, environmentHash);

        List<Double> latencies = new ArrayList<>();
        int successes = 0;
        int failures = 0;

        long measuredStartNs = System.nanoTime();
        for (int i = 0; i < config.executionsPerRun; i++) {
            long runElapsedMs = (System.nanoTime() - measuredStartNs) / 1_000_000L;
            if (runElapsedMs >= config.maxRunDurationMs) {
                break;
            }

            ExecutionResult outcome = runOneExecution(config, evidencePurpose);
            double latencyMs = (outcome.endNs - outcome.startNs) / 1_000_000.0;
            int sampleIndex = i;

            if (outcome.success && outcome.documentId != null) {
                runtimeDocumentFacts.add(captureDocumentProvenance(outcome.documentId));
            }

            BenchmarkRawSample sample = buildSample(
                    evidencePurpose, runSetDir, runId, sampleIndex, latencyMs, outcome,
                    measuredStartNs, config, configHash, environmentHash);
            writer.write(sample);

            latencies.add(latencyMs);
            if (outcome.success) {
                successes++;
            } else {
                failures++;
            }

            if (outcome.success && outcome.documentId != null) {
                cleanupCompletedExecution(outcome.documentId);
            }
        }

        if (latencies.size() != config.executionsPerRun) {
            throw new IllegalStateException("exact N not completed: " + latencies.size()
                    + "/" + config.executionsPerRun);
        }
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("warmup_completed", config.warmupSamples);
        observation.put("max_in_flight_observed", 1);
        BenchmarkRunSetManager.recordRunObservation(
                runSetDir, MODE, runId, writer.getRawFile(), observation);

        if (failures > 0) {
            throw new IllegalStateException(
                    "authoritative document-processing executions failed: " + failures
                            + "/" + config.executionsPerRun);
        }

        return new RunResult(latencies, successes, failures);
    }

    /**
     * Removes a completed measured document outside the latency boundary so
     * the next execution can submit the exact same frozen source bytes without
     * tripping the production checksum duplicate guard.
     */
    private void cleanupCompletedExecution(Long documentId) {
        documentService.deleteDocument(
                benchWorkspace.getId(), benchKb.getId(), documentId, benchUser.getId());
        if (documentMapper.selectById(documentId) != null) {
            throw new IllegalStateException(
                    "measured document cleanup did not hard-delete row: " + documentId);
        }
        uploadedDocumentIds.remove(documentId);
    }

    private ExecutionResult runOneExecution(
            Config config, BenchmarkRunSetManager.EvidencePurpose evidencePurpose) {
        String fileName = "bench-doc-" + UUID.randomUUID() + ".txt";
        MockMultipartFile file = new MockMultipartFile(
                "file", fileName, "text/plain", SAMPLE_DOC_BYTES);

        long startNs = System.nanoTime();
        DocumentUploadResponse response;
        try {
            response = documentService.uploadDocument(
                    benchWorkspace.getId(), benchKb.getId(), benchUser.getId(), file);
        } catch (Exception e) {
            long endNs = System.nanoTime();
            return new ExecutionResult(startNs, endNs, false,
                    "upload_failed: " + e.getMessage(), null);
        }

        Long documentId = response.getDocumentId();
        if (documentId != null) {
            uploadedDocumentIds.add(documentId);
        }

        boolean completed = waitForCompletion(
                documentId, config.timeoutMs,
                evidencePurpose == BenchmarkRunSetManager.EvidencePurpose.FORMAL_BENCHMARK_CANDIDATE);
        long endNs = System.nanoTime();

        if (!completed) {
            return new ExecutionResult(startNs, endNs, false,
                    "timeout/failure waiting for document COMPLETED and retrieval READY", documentId);
        }
        return new ExecutionResult(startNs, endNs, true, null, documentId);
    }

    private boolean waitForCompletion(Long documentId, long timeoutMs, boolean requireIndexReady) {
        if (documentId == null) {
            return false;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            KnowledgeDocument doc = documentMapper.selectById(documentId);
            if (doc == null) {
                return false;
            }
            if (DocumentStatus.COMPLETED.getValue().equals(doc.getStatus())) {
                if (!requireIndexReady) {
                    return true;
                }
                DocumentRetrievalTask retrievalTask = findRetrievalTask(documentId);
                if (retrievalTask != null
                        && RetrievalTaskStatus.READY.getValue().equals(retrievalTask.getStatus())) {
                    return true;
                }
                if (retrievalTask != null
                        && (RetrievalTaskStatus.FAILED.getValue().equals(retrievalTask.getStatus())
                        || RetrievalTaskStatus.CANCELLED.getValue().equals(retrievalTask.getStatus()))) {
                    return false;
                }
            }
            if (DocumentStatus.FAILED.getValue().equals(doc.getStatus())) {
                return false;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private DocumentRetrievalTask findRetrievalTask(Long documentId) {
        return documentRetrievalTaskMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentRetrievalTask>()
                        .eq(DocumentRetrievalTask::getDocumentId, documentId)
                        .orderByDesc(DocumentRetrievalTask::getGeneration)
                        .last("LIMIT 1"));
    }

    private DocumentIndexTask findDocumentIndexTask(Long documentId) {
        return documentIndexTaskMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentIndexTask>()
                        .eq(DocumentIndexTask::getDocumentId, documentId)
                        .orderByDesc(DocumentIndexTask::getId)
                        .last("LIMIT 1"));
    }

    private Map<String, Object> captureServiceProvenanceSnapshot(String capturePhase) throws Exception {
        Map<String, Object> probes = new LinkedHashMap<>();
        probes.put("observed_at", Instant.now().toString());
        probes.put("source", "DocumentProcessingBenchmarkHarness.liveRuntimeClients");
        probes.put("capture_phase", capturePhase);

        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            Map<String, Object> jdbc = new LinkedHashMap<>();
            jdbc.put("observed_at", Instant.now().toString());
            jdbc.put("source", "DataSource.getConnection/JdbcTemplate");
            jdbc.put("probe", "live_connection");
            jdbc.put("valid", connection.isValid(2));
            jdbc.put("database_product", metadata.getDatabaseProductName());
            jdbc.put("database_version", metadata.getDatabaseProductVersion());
            jdbc.put("driver_name", metadata.getDriverName());
            jdbc.put("driver_version", metadata.getDriverVersion());
            jdbc.put("select_one", jdbcTemplate.queryForObject("SELECT 1", Integer.class));
            probes.put("jdbc", jdbc);
        }

        Map<String, Object> minio = new LinkedHashMap<>();
        minio.put("observed_at", Instant.now().toString());
        minio.put("source", "MinioClient.bucketExists");
        minio.put("probe", "live_bucket_exists");
        minio.put("endpoint", minioProperties.getEndpoint());
        minio.put("bucket", minioProperties.getBucket());
        minio.put("bucket_exists", minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(minioProperties.getBucket()).build()));
        probes.put("minio", minio);

        Map<String, Object> rabbit = new LinkedHashMap<>();
        rabbit.put("observed_at", Instant.now().toString());
        rabbit.put("source", "RabbitTemplate.connectionFactory/queueDeclarePassive/listenerRegistry");
        rabbit.put("probe", "live_connection_queue_listener");
        ConnectionFactory connectionFactory = rabbitTemplate.getConnectionFactory();
        try (Connection connection = connectionFactory.createConnection()) {
            Map<String, Object> server = connection.getDelegate().getServerProperties();
            rabbit.put("server_product", rabbitServerProperty(server.get("product")));
            rabbit.put("server_version", rabbitServerProperty(server.get("version")));
            rabbit.put("server_platform", rabbitServerProperty(server.get("platform")));
            rabbit.put("open", connection.isOpen());
        }
        rabbit.put("document_queue", queueFacts(rabbitMqProperties.getDocumentQueue()));
        rabbit.put("retrieval_queue", queueFacts(retrievalRabbitMqProperties.getQueue()));
        rabbit.put("listener_containers", listenerRegistry.getListenerContainers().size());
        rabbit.put("listeners_running", listenerRegistry.getListenerContainers().stream()
                .filter(container -> container.isRunning()).count());
        probes.put("rabbitmq", rabbit);
        return probes;
    }

    private static String rabbitServerProperty(Object value) {
        return value == null ? "unknown" : value.toString();
    }

    private Map<String, Object> queueFacts(String queueName) {
        return rabbitTemplate.execute(channel -> {
            var result = channel.queueDeclarePassive(queueName);
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("observed_at", Instant.now().toString());
            facts.put("source", "RabbitTemplate.queueDeclarePassive");
            facts.put("probe", "live_queue_declare_passive");
            facts.put("name", queueName);
            facts.put("message_count", result.getMessageCount());
            facts.put("consumer_count", result.getConsumerCount());
            return facts;
        });
    }

    private Map<String, Object> captureDocumentProvenance(Long documentId) throws Exception {
        KnowledgeDocument document = documentMapper.selectById(documentId);
        DocumentIndexTask indexTask = findDocumentIndexTask(documentId);
        DocumentRetrievalTask retrievalTask = findRetrievalTask(documentId);
        List<DocumentChunk> chunks = documentChunkMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getDocumentId, documentId)
                        .orderByAsc(DocumentChunk::getChunkIndex));

        DocumentFormat format = documentFormatDetector.detect(
                document.getOriginalFileName(), document.getContentType(), SAMPLE_DOC_BYTES);
        String parserClass = parserRegistry.getParser(format).getClass().getSimpleName();
        List<Map<String, Object>> chunkIdentities = chunks.stream().map(chunk -> {
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("chunk_index", chunk.getChunkIndex());
            identity.put("content_sha256", EvalHashing.sha256Hex(chunk.getContent()));
            identity.put("embedded", chunk.getEmbeddedAt() != null);
            identity.put("embedding_model", chunk.getEmbeddingModel());
            return identity;
        }).toList();

        var objectStat = minioClient.statObject(StatObjectArgs.builder()
                .bucket(document.getBucketName()).object(document.getObjectKey()).build());

        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("observed_at", Instant.now().toString());
        fact.put("source", "live persistence entities/MinioClient.statObject/parser registry");
        fact.put("probe", "post-terminal-document-fact-chain");
        fact.put("document_id", documentId);
        fact.put("document_status", document.getStatus());
        fact.put("document_completed_at", String.valueOf(document.getCompletedAt()));
        fact.put("document_index_task_id", indexTask != null ? indexTask.getId() : null);
        fact.put("document_index_message_id", indexTask != null ? indexTask.getMessageId() : null);
        fact.put("document_index_task_status", indexTask != null ? indexTask.getStatus() : null);
        fact.put("document_index_attempt_count", indexTask != null ? indexTask.getAttemptCount() : null);
        fact.put("parser_format", format != null ? format.name() : null);
        fact.put("parser_class", parserClass);
        fact.put("parser_metadata_sha256", EvalHashing.sha256Hex(
                document.getParserMetadata() == null ? "" : document.getParserMetadata()));
        fact.put("chunk_count", chunks.size());
        fact.put("chunk_digest", EvalHashing.sha256Hex(EvalHashing.canonicalJson(chunkIdentities)));
        fact.put("retrieval_task_id", retrievalTask != null ? retrievalTask.getId() : null);
        fact.put("retrieval_task_status", retrievalTask != null ? retrievalTask.getStatus() : null);
        fact.put("retrieval_generation", retrievalTask != null ? retrievalTask.getGeneration() : null);
        fact.put("embedding_model", retrievalTask != null
                ? retrievalTask.getEmbeddingModel() : embeddingProperties.getModel());
        fact.put("embedding_dimension", retrievalTask != null
                ? retrievalTask.getEmbeddingDimension() : embeddingProperties.getDimension());
        fact.put("elasticsearch_index", retrievalTask != null ? retrievalTask.getEsIndexName() : null);
        fact.put("indexed_chunk_count", retrievalTask != null ? retrievalTask.getIndexedChunkCount() : 0);
        fact.put("minio_bucket", document.getBucketName());
        fact.put("minio_object_key_sha256", EvalHashing.sha256Hex(document.getObjectKey()));
        fact.put("minio_object_size", objectStat.size());
        fact.put("minio_object_etag", objectStat.etag());
        return fact;
    }

    private BenchmarkRawSample buildSample(BenchmarkRunSetManager.EvidencePurpose evidencePurpose,
                                           Path runSetDir, String runId, int sampleIndex,
                                           double latencyMs, ExecutionResult outcome,
                                           long measuredStartNs, Config config,
                                           String configHash, String environmentHash) {
        String timestamp = Instant.now().toString();
        double runRelativeTime = (System.nanoTime() - measuredStartNs) / 1_000_000.0;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", MODE);
        metadata.put("description",
                "End-to-end document upload through real PostgreSQL/MinIO/RabbitMQ/consumer/parser/chunk path");
        metadata.put("target", "DocumentService.uploadDocument");
        metadata.put("sample_type", "measured");
        metadata.put("user_id", benchUser.getId());
        metadata.put("workspace_id", benchWorkspace.getId());
        metadata.put("knowledge_base_id", benchKb.getId());
        metadata.put("document_id", outcome.documentId);
        metadata.put("request_timeout_ms", config.timeoutMs);
        metadata.put("evidence_mode", MODE);
        if (evidencePurpose == BenchmarkRunSetManager.EvidencePurpose.SMOKE) {
            metadata.put("evidence_tag", "NOT_BENCHMARK_EVIDENCE");
        }

        return new BenchmarkRawSample(
                timestamp,
                SCENARIO,
                runSetDir.getFileName().toString(),
                runId,
                sampleIndex,
                "processing_duration",
                "e2e://document-processing",
                latencyMs,
                null,
                outcome.success ? 200 : 500,
                outcome.success,
                outcome.error,
                1,
                config.concurrency,
                PROVIDER_MODE,
                configHash,
                environmentHash,
                runId + "/" + sampleIndex,
                metadata,
                runRelativeTime,
                null,
                null,
                null);
    }

    // -------------------------------------------------------------------------
    // Smoke tests
    // -------------------------------------------------------------------------

    @Test
    void formalBenchmarkWhenEnabled() throws Exception {
        if (!Boolean.getBoolean("intellidesk.benchmark.formal")) {
            return;
        }
        String runSetId = System.getProperty(RUN_SET_ID_PROPERTY);
        if (runSetId == null || runSetId.isBlank()) {
            throw new IllegalArgumentException("-D" + RUN_SET_ID_PROPERTY + " is required for formal execution");
        }
        Summary summary = execute(resolveBenchRoot(), Config.canonical().withRunSetId(runSetId.trim()),
                BenchmarkRunSetManager.EvidencePurpose.FORMAL_BENCHMARK_CANDIDATE);
        boolean cleanupSuccess = cleanupProvisionedResources();
        BenchmarkRunSetManager.recordCleanup(summary.runSetDir, cleanupSuccess,
                Map.of("documents_removed", true, "knowledge_base_removed", true,
                        "workspace_removed", true, "user_removed", true));
        if (!cleanupSuccess) {
            throw new IllegalStateException("mandatory Document Processing cleanup failed");
        }
    }

    @Test
    void smokeRunProducesCompleteRunSet() throws Exception {
        Config config = Config.smoke();
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        Summary summary = execute(benchRoot, config);

        assertNotNull(summary.runSetDir);
        assertTrue(Files.exists(summary.runSetDir));
        assertEquals(computeConfigHash(config), summary.configHash);
        assertEquals(BenchmarkRunSetManager.Status.PARTIAL,
                BenchmarkRunSetManager.readStatus(summary.runSetDir));

        Path rawFile = findRawFile(summary.runSetDir);
        assertTrue(Files.exists(rawFile));
        List<String> lines = Files.readAllLines(rawFile);
        assertFalse(lines.isEmpty());
    }

    @Test
    void rawSchemaHasMandatoryFields() throws Exception {
        Config config = Config.smoke();
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        Summary summary = execute(benchRoot, config);
        Path rawFile = findRawFile(summary.runSetDir);
        String firstLine = Files.readAllLines(rawFile).get(0);
        Map<String, Object> parsed = OM.readValue(firstLine, LinkedHashMap.class);

        List<String> mandatory = List.of(
                "timestamp", "scenario", "run_set_id", "run_id", "sample_index",
                "metric_name", "endpoint", "latency_ms", "status_code", "success",
                "concurrency", "provider_mode", "config_hash", "environment_hash",
                "execution_metadata");
        for (String field : mandatory) {
            assertTrue(parsed.containsKey(field), "missing mandatory field: " + field);
        }

        assertEquals(SCENARIO, parsed.get("scenario"));
        assertEquals(PROVIDER_MODE, parsed.get("provider_mode"));
        assertEquals(computeConfigHash(config), parsed.get("config_hash"));

        Map<String, Object> metadata = (Map<String, Object>) parsed.get("execution_metadata");
        assertNotNull(metadata);
        assertEquals(MODE, metadata.get("mode"));
    }

    @Test
    void exactNExecutionsPerRun() throws Exception {
        Config config = Config.smoke().withExecutions(3);
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        Summary summary = execute(benchRoot, config);
        Path rawFile = findRawFile(summary.runSetDir);
        List<String> lines = Files.readAllLines(rawFile);
        assertEquals(3, lines.size());
        for (String line : lines) {
            Map<String, Object> sample = OM.readValue(line, LinkedHashMap.class);
            assertEquals(Boolean.TRUE, sample.get("success"),
                    "every repeated-source execution must exercise the real pipeline successfully");
            assertNull(sample.get("error"));
        }
    }

    @Test
    void failIfExistsOnDuplicateRunSet() throws Exception {
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        String configHash = computeConfigHash(Config.smoke());
        String runSetId = "fail-if-exists-document-processing-001";

        Path first = BenchmarkRunSetManager.createRunSet(
                benchRoot, SCENARIO, runSetId, configHash, List.of(MODE), 1);
        assertTrue(Files.exists(first));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                BenchmarkRunSetManager.createRunSet(
                        benchRoot, SCENARIO, runSetId, configHash, List.of(MODE), 1));
        assertTrue(ex.getMessage().contains("FAIL_IF_EXISTS"));
    }

    private Path findRawFile(Path runSetDir) throws IOException {
        String prefix = "raw-" + SCENARIO + "-" + MODE + "-";
        try (var stream = Files.list(runSetDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith(prefix)
                            && p.getFileName().toString().endsWith(".jsonl"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("raw file not found in " + runSetDir));
        }
    }
}
