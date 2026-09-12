package com.intellidesk.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.chat.ChatLlmProperties;
import com.intellidesk.chat.conversation.Conversation;
import com.intellidesk.chat.conversation.ConversationService;
import com.intellidesk.chat.dto.ChatRequest;
import com.intellidesk.chat.message.ChatMessage;
import com.intellidesk.chat.message.ChatMessageMapper;
import com.intellidesk.chat.message.ChatMessageStatus;
import com.intellidesk.chat.orchestration.ChatOrchestrationService;
import com.intellidesk.evaluation.EvalHashing;
import com.intellidesk.knowledge.KnowledgeBase;
import com.intellidesk.knowledge.KnowledgeBaseService;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import com.intellidesk.workspace.Workspace;
import com.intellidesk.workspace.WorkspaceService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase 8 Wave 2 — B-class end-to-end RAG completion benchmark harness.
 *
 * <p>Exercises the full RAG chat pipeline through
 * {@link ChatOrchestrationService#chat(ChatRequest, Long, Long, Long)} using a
 * deterministic E2E stub for the LLM provider. The system pipeline (validation,
 * conversation ownership, retrieval, context build, prompt construction,
 * streaming, finalization) is real.
 *
 * <p>Provider mode is {@code e2e-deterministic-stub}; real provider latency is
 * observed-only and not used as a stable SLA.
 *
 * <p>Smoke tests run under the {@code test} profile with a mocked ChatModel and
 * are marked {@code NOT_BENCHMARK_EVIDENCE} in raw metadata.
 */
@SpringBootTest
@ActiveProfiles({"test", "bench"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(TestInfrastructureConfig.class)
public class RagCompletionBenchmarkHarness {

    public static final String SCENARIO = "rag-completion";
    public static final String MODE = "e2e-stub";
    public static final String DEFAULT_RUN_SET_ID = "rag-completion-bench-001";
    public static final String RUN_SET_ID_PROPERTY = "ragCompletionBenchRunSetId";

    // Approved parameters from Phase 8 Wave 2 plan v2.2 §1.1.
    public static final int WARMUP_SAMPLES = 1;
    public static final int EXECUTIONS_PER_RUN = 30;
    public static final int INDEPENDENT_RUNS = 3;
    public static final long TIMEOUT_MS = 60_000L;
    public static final long MAX_RUN_DURATION_MS = 40 * 60 * 1000L;
    public static final int CONCURRENCY = 1;
    public static final String PROVIDER_MODE = "e2e-deterministic-stub";
    public static final String PERCENTILE_METHOD = "nearest-rank";

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
            return new Config(1, 2, 1, 10_000L, 60_000L, 1,
                    "rag-completion-smoke-" + UUID.randomUUID());
        }

        public Config withWarmup(int warmupSamples) {
            return new Config(warmupSamples, executionsPerRun, runs, timeoutMs,
                    maxRunDurationMs, concurrency, runSetId);
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
        private final Long assistantMessageId;

        public ExecutionResult(long startNs, long endNs, boolean success,
                               String error, Long assistantMessageId) {
            this.startNs = startNs;
            this.endNs = endNs;
            this.success = success;
            this.error = error;
            this.assistantMessageId = assistantMessageId;
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

    @Autowired
    private ChatOrchestrationService chatOrchestrationService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private UserService userService;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private ChatLlmProperties chatLlmProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    private Path tempDir;

    private User benchUser;
    private Workspace benchWorkspace;
    private KnowledgeBase benchKb;
    private Conversation benchConversation;

    @BeforeAll
    void provision() {
        // Stub the LLM so the RAG completion pipeline is deterministic and fast.
        org.slf4j.LoggerFactory.getLogger(RagCompletionBenchmarkHarness.class)
                .info("ChatModel bean class: {}", chatModel.getClass().getName());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("benchmark answer"))))
        ));

        benchUser = userService.register(
                "bench-rag-completion-user-" + UUID.randomUUID(),
                "bench-password-" + UUID.randomUUID(),
                "bench-" + UUID.randomUUID() + "@example.com",
                "Bench RAG User");

        benchWorkspace = workspaceService.createWorkspace(
                "bench-rag-completion-workspace-" + UUID.randomUUID(),
                "RAG completion benchmark workspace",
                benchUser.getId());

        benchKb = knowledgeBaseService.createKnowledgeBase(
                benchWorkspace.getId(), benchUser.getId(),
                "bench-rag-completion-kb-" + UUID.randomUUID(),
                "RAG completion benchmark KB",
                "RECURSIVE", 512, 64);

        benchConversation = conversationService.create(
                benchWorkspace.getId(), benchUser.getId(),
                "bench-rag-completion-conversation");
    }

    @AfterAll
    void cleanup() {
        cleanupProvisionedResources();
    }

    private boolean cleanupProvisionedResources() {
        boolean success = true;
        if (benchConversation != null && benchConversation.getId() != null) {
            try {
                conversationService.delete(benchConversation.getId(), benchWorkspace.getId(), benchUser.getId());
            } catch (Exception error) {
                success = false;
            }
            benchConversation = null;
        }
        if (benchKb != null && benchKb.getId() != null) {
            try {
                knowledgeBaseService.deleteKnowledgeBase(
                        benchWorkspace.getId(), benchKb.getId(), benchUser.getId());
            } catch (Exception error) {
                success = false;
            }
            benchKb = null;
        }
        if (benchWorkspace != null && benchWorkspace.getId() != null) {
            try {
                workspaceService.deleteWorkspace(benchWorkspace.getId(), benchUser.getId());
            } catch (Exception error) {
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

    /**
     * Resolves the project-level benchmark root ({@code docs/evaluation/bench})
     * relative to the backend working directory used by Maven.
     */
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
        cfg.put("target", "ChatOrchestrationService.chat");
        cfg.put("provider_mode", PROVIDER_MODE);
        cfg.put("warmup_samples", config.warmupSamples);
        cfg.put("executions_per_run", config.executionsPerRun);
        cfg.put("runs", config.runs);
        cfg.put("timeout_ms", config.timeoutMs);
        cfg.put("max_run_duration_ms", config.maxRunDurationMs);
        cfg.put("concurrency", config.concurrency);
        cfg.put("independent_runs", INDEPENDENT_RUNS);
        cfg.put("percentile_method", PERCENTILE_METHOD);
        cfg.put("success_semantics", "SSE stream completes and assistant message finalized to SUCCESS");
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
        env.put("chat_model", chatLlmProperties.getModel());
        return env;
    }

    private String computeEnvironmentHash() {
        return BenchmarkRunSetManager.canonicalHash(buildEnvironmentIdentity());
    }

    /**
     * Executes the benchmark and persists raw per-execution records.
     */
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

        ChatRequest request = buildRequest();

        List<RunResult> results = new ArrayList<>();
        try {
            for (int i = 1; i <= config.runs; i++) {
                RunResult result = runOneRun("run-" + i, runSetDir, config, request,
                        configHash, environmentHash, evidencePurpose);
                results.add(result);
            }
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

    private ChatRequest buildRequest() {
        ChatRequest request = new ChatRequest();
        request.setQuery("benchmark query for RAG completion");
        request.setKnowledgeBaseIds(List.of(benchKb.getId()));
        request.setRewriteEnabled(false);
        request.setCandidateTopK(10);
        request.setTopK(5);
        request.setRerank(false);
        return request;
    }

    private RunResult runOneRun(String runId, Path runSetDir, Config config,
                                ChatRequest request, String configHash,
                                String environmentHash,
                                BenchmarkRunSetManager.EvidencePurpose evidencePurpose) throws IOException {
        BenchmarkRawWriter writer = new BenchmarkRawWriter(
                runSetDir, SCENARIO, MODE, runId, configHash, environmentHash);

        // Warmup: discard; not written to raw.
        int warmupCompleted = 0;
        for (int i = 0; i < config.warmupSamples; i++) {
            ExecutionResult warmup = runOneExecution(request, config.timeoutMs);
            if (!warmup.success) {
                throw new IllegalStateException("RAG completion warmup failed: " + warmup.error);
            }
            warmupCompleted++;
        }

        List<Double> latencies = new ArrayList<>();
        int successes = 0;
        int failures = 0;

        long measuredStartNs = System.nanoTime();
        for (int i = 0; i < config.executionsPerRun; i++) {
            long runElapsedMs = (System.nanoTime() - measuredStartNs) / 1_000_000L;
            if (runElapsedMs >= config.maxRunDurationMs) {
                break;
            }

            ExecutionResult outcome = runOneExecution(request, config.timeoutMs);
            double latencyMs = (outcome.endNs - outcome.startNs) / 1_000_000.0;
            int sampleIndex = i;

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
        }

        if (latencies.size() != config.executionsPerRun) {
            throw new IllegalStateException("exact N not completed: " + latencies.size()
                    + "/" + config.executionsPerRun);
        }
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("warmup_completed", warmupCompleted);
        observation.put("max_in_flight_observed", 1);
        BenchmarkRunSetManager.recordRunObservation(
                runSetDir, MODE, runId, writer.getRawFile(), observation);

        return new RunResult(latencies, successes, failures);
    }

    private ExecutionResult runOneExecution(ChatRequest request) {
        return runOneExecution(request, TIMEOUT_MS);
    }

    private ExecutionResult runOneExecution(ChatRequest request, long timeoutMs) {
        long startNs = System.nanoTime();
        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        SseEmitter emitter = chatOrchestrationService.chat(
                request, benchWorkspace.getId(), benchConversation.getId(), benchUser.getId());

        // Register emitter callbacks defensively, but do not rely on them as the
        // authoritative completion signal. The streaming executor runs concurrently
        // and may call emitter.complete() before the callback is registered, in
        // which case the callback never fires and a latch-only wait would time out.
        CountDownLatch latch = new CountDownLatch(1);
        emitter.onCompletion(latch::countDown);
        emitter.onError(e -> latch.countDown());
        emitter.onTimeout(() -> latch.countDown());

        // DB-polling is the authoritative completion signal: the orchestrator
        // persists the final ASSISTANT status (SUCCESS / FAILED / CANCELLED)
        // before completing the emitter.
        ChatMessage finalMessage = pollForFinalAssistantMessage(deadlineNs, latch);
        long endNs = System.nanoTime();

        if (finalMessage == null) {
            return new ExecutionResult(startNs, endNs, false, "timeout", null);
        }

        boolean success = ChatMessageStatus.SUCCESS.name().equals(finalMessage.getStatus());
        String error = success ? null : "assistant_status=" + finalMessage.getStatus()
                + ", role=" + finalMessage.getRole()
                + ", errorCode=" + finalMessage.getErrorCode();
        return new ExecutionResult(startNs, endNs, success, error, finalMessage.getId());
    }

    private ChatMessage pollForFinalAssistantMessage(long deadlineNs, CountDownLatch latch) {
        try {
            while (System.nanoTime() < deadlineNs) {
                long remainingNs = deadlineNs - System.nanoTime();
                long waitMs = Math.min(200L, Math.max(1L, remainingNs / 1_000_000L));
                boolean fired = latch.await(waitMs, TimeUnit.MILLISECONDS);

                ChatMessage latest = findLatestAssistantMessage();
                if (latest != null && !ChatMessageStatus.GENERATING.name().equals(latest.getStatus())) {
                    return latest;
                }

                if (fired) {
                    // Callback fired but DB may still show GENERATING briefly;
                    // allow one short retry before continuing the poll loop.
                    Thread.sleep(50L);
                    latest = findLatestAssistantMessage();
                    if (latest != null && !ChatMessageStatus.GENERATING.name().equals(latest.getStatus())) {
                        return latest;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private ChatMessage findLatestAssistantMessage() {
        List<ChatMessage> messages = chatMessageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, benchConversation.getId())
                        .eq(ChatMessage::getRole, "ASSISTANT")
                        .orderByDesc(ChatMessage::getCreatedAt)
                        .last("LIMIT 1"));
        return messages.isEmpty() ? null : messages.get(0);
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
                "End-to-end RAG completion through ChatOrchestrationService with deterministic LLM stub");
        metadata.put("target", "ChatOrchestrationService.chat");
        metadata.put("sample_type", "measured");
        metadata.put("user_id", benchUser.getId());
        metadata.put("workspace_id", benchWorkspace.getId());
        metadata.put("knowledge_base_id", benchKb.getId());
        metadata.put("conversation_id", benchConversation.getId());
        metadata.put("assistant_message_id", outcome.assistantMessageId);
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
                "completion_duration",
                "e2e://rag-completion",
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
                Map.of("conversation_removed", true, "knowledge_base_removed", true,
                        "workspace_removed", true, "user_removed", true));
        if (!cleanupSuccess) {
            throw new IllegalStateException("mandatory RAG completion cleanup failed");
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
    void warmupSamplesExcludedFromRaw() throws Exception {
        Config config = Config.smoke()
                .withWarmup(1)
                .withExecutions(2);
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        Summary summary = execute(benchRoot, config);
        Path rawFile = findRawFile(summary.runSetDir);
        List<String> lines = Files.readAllLines(rawFile);

        assertEquals(2, lines.size(), "only measured executions should be persisted");
        for (String line : lines) {
            Map<String, Object> parsed = OM.readValue(line, LinkedHashMap.class);
            int sampleIndex = ((Number) parsed.get("sample_index")).intValue();
            assertTrue(sampleIndex >= 0 && sampleIndex < 2,
                    "raw indices must be measured-only and zero-based");
        }
    }

    @Test
    void rawSchemaHasMandatoryFields() throws Exception {
        Config config = Config.smoke();
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        Summary summary = execute(benchRoot, config);
        Path rawFile = findRawFile(summary.runSetDir);
        List<String> lines = Files.readAllLines(rawFile);
        String firstLine = lines.get(0);
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
        assertEquals(200, parsed.get("status_code"));
        assertEquals(Boolean.TRUE, parsed.get("success"));
        assertEquals(computeConfigHash(config), parsed.get("config_hash"));

        Map<String, Object> metadata = (Map<String, Object>) parsed.get("execution_metadata");
        assertNotNull(metadata);
        assertEquals(MODE, metadata.get("mode"));
    }

    @Test
    void exactNExecutionsPerRun() throws Exception {
        Config config = Config.smoke()
                .withWarmup(0)
                .withExecutions(3);
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        Summary summary = execute(benchRoot, config);
        Path rawFile = findRawFile(summary.runSetDir);
        List<String> lines = Files.readAllLines(rawFile);
        assertEquals(3, lines.size());
    }

    @Test
    void independentRunIdentityPreserved() throws Exception {
        Config config1 = Config.smoke()
                .withWarmup(0)
                .withExecutions(1);
        Config config2 = Config.smoke()
                .withWarmup(0)
                .withExecutions(1);
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        Summary summary1 = execute(benchRoot, config1);
        Summary summary2 = execute(benchRoot, config2);

        assertNotEquals(summary1.runSetDir.getFileName().toString(),
                summary2.runSetDir.getFileName().toString());
    }

    @Test
    void configHashMatchesHarnessConfig() throws Exception {
        Config config = Config.smoke();
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        Summary summary = execute(benchRoot, config);
        Path rawFile = findRawFile(summary.runSetDir);
        String expectedConfigHash = computeConfigHash(config);
        String expectedEnvironmentHash = computeEnvironmentHash();

        for (String line : Files.readAllLines(rawFile)) {
            Map<String, Object> parsed = OM.readValue(line, LinkedHashMap.class);
            assertEquals(expectedConfigHash, parsed.get("config_hash"));
            assertEquals(expectedEnvironmentHash, parsed.get("environment_hash"));
        }
    }

    @Test
    void failIfExistsOnDuplicateRunSet() throws Exception {
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        String configHash = computeConfigHash(Config.smoke());
        String runSetId = "fail-if-exists-rag-completion-001";

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
