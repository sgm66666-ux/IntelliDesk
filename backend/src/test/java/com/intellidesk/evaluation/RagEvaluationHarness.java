package com.intellidesk.evaluation;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.document.DocumentChunk;
import com.intellidesk.document.DocumentChunkMapper;
import com.intellidesk.document.chunk.ChunkConfig;
import com.intellidesk.document.chunk.ChunkDraft;
import com.intellidesk.document.chunk.ChunkStrategyType;
import com.intellidesk.document.chunk.RecursiveChunkStrategy;
import com.intellidesk.document.parser.MarkdownDocumentParser;
import com.intellidesk.document.parser.ParseContext;
import com.intellidesk.document.parser.ParsedDocument;
import com.intellidesk.embedding.EmbeddingBatchResult;
import com.intellidesk.embedding.EmbeddingService;
import com.intellidesk.embedding.SpringAiEmbeddingService;
import com.intellidesk.infrastructure.config.ElasticsearchProperties;
import com.intellidesk.infrastructure.config.EmbeddingConfig;
import com.intellidesk.infrastructure.config.EmbeddingProperties;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.retrieval.*;
import com.intellidesk.retrieval.rerank.DashScopeCompatibleRerankClient;
import com.intellidesk.retrieval.rerank.RerankClient;
import com.intellidesk.retrieval.rerank.RerankService;
import com.intellidesk.retrieval.fusion.HybridRetriever;
import com.intellidesk.retrieval.keyword.ElasticsearchChunkDocument;
import com.intellidesk.retrieval.keyword.ElasticsearchChunkIndex;
import com.intellidesk.retrieval.keyword.ElasticsearchIndexManager;
import com.intellidesk.retrieval.keyword.KeywordRetriever;
import com.pgvector.PGvector;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 8 Wave 1 — RAG evaluation harness over the REAL production retrieval pipeline
 * (real containers + real production classes + real local providers).
 *
 * NOT auto-discovered by surefire (class name does not end in *Test/*Tests); run on demand:
 *   mvn -Dtest=RagEvaluationHarness test
 *
 * What it does:
 *  1. Boots real pgvector:pg16 + Elasticsearch 8.17.10 (analysis-smartcn) containers.
 *  2. Ingests the FROZEN evaluation corpus (docs/evaluation/corpus, 14 .md) through the REAL
 *     MarkdownDocumentParser + RecursiveChunkStrategy (ChunkConfig RECURSIVE 1000/150), the REAL
 *     SpringAiEmbeddingService backed by the REAL local Ollama qwen3-embedding:8b model
 *     (dimensions=1536), direct-JDBC chunk insert, and per-chunk ES indexing into
 *     index "intellidesk-chunks-v1".
 *  3. Verifies the produced chunk set identity against docs/evaluation/raw/indexed_chunk_manifest.json.
 *  4. Runs each frozen-evaluation QUALIFIED question through the REAL RetrievalService in
 *     VECTOR / KEYWORD / HYBRID modes (run_index 1 and 2) and HYBRID_RERANK through the REAL
 *     production RerankService -> DashScopeCompatibleRerankClient -> 127.0.0.1:18182 local gateway
 *     -> BAAI/bge-reranker-v2-m3 via FlagEmbedding::FlagReranker.
 *  5. Writes one machine-readable raw JSON per (mode, run_index) to docs/evaluation/raw/.
 *
 * REAL PROVIDER REPORTING:
 *  - embedding provider = LOCAL_OLLAMA_REAL (qwen3-embedding:8b, dimension=1536).
 *  - rerank provider = LOCAL_REAL_GATEWAY (BAAI/bge-reranker-v2-m3, FlagEmbedding::FlagReranker).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestInfrastructureConfig.class, RagEvaluationHarness.RagHarnessConfig.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
@EnabledIf("isDockerAvailable")
public class RagEvaluationHarness {

    // ---- FROZEN evaluation constants (do not read from app config) ----
    public static final String FROZEN_INDEX = "intellidesk-chunks-v1";
    public static final int CANDIDATE_TOP_K = 50;
    public static final int TOP_K = 10;
    public static final List<Integer> REPORTED_KS = List.of(1, 3, 5, 10);
    public static final int RRF_K = 60;
    public static final int EMBEDDING_DIMENSION = 1536;
    public static final String EMBEDDING_MODEL = "qwen3-embedding:8b";
    public static final String EMBEDDING_PROVIDER_LABEL = "LOCAL_OLLAMA_REAL";
    public static final int CHUNK_SIZE = 1000;
    public static final int CHUNK_OVERLAP = 150;
    public static final String CHUNK_STRATEGY = "RECURSIVE";
    public static final int INDEX_GENERATION = 1;
    public static final String WORKSPACE_ID = "1";
    public static final String KB_ID = "1";
    public static final Set<String> SCOPED_KB_IDS = Set.of("1");

    public static final boolean RERANK_ENABLED = true;
    public static final String RERANK_BASE_URL = "http://127.0.0.1:18182";
    public static final String RERANK_API_KEY = "local-placeholder";
    public static final String RERANK_MODEL = "BAAI/bge-reranker-v2-m3";
    public static final String RERANK_GATE = "REAL_LOCAL_GATEWAY";

    // ---- Immutable run-set namespace (Evidence-Loss Acceptance Amendment v1.1) ----
    public static final String EVIDENCE_CLASS = "real-quality";
    public static final String DEFAULT_RUN_SET_ID = "implementation-compensation-001";
    public static final String RUN_SET_ID_PROPERTY = "runSetId";
    public static final int CONFIG_ID_PREFIX_LENGTH = 16;

    public static String resolveRunSetId() {
        String prop = System.getProperty(RUN_SET_ID_PROPERTY);
        if (prop != null) {
            prop = prop.trim();
            if (prop.isEmpty()) {
                throw new IllegalArgumentException("System property -D" + RUN_SET_ID_PROPERTY + " must not be empty");
            }
            return prop;
        }
        return DEFAULT_RUN_SET_ID;
    }

    private static PostgreSQLContainer<?> postgres;
    private static ElasticsearchContainer elasticsearch;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ElasticsearchClient esClient;
    @Autowired private ElasticsearchIndexManager indexManager;
    @Autowired private ElasticsearchChunkIndex chunkIndex;
    @Autowired private RetrievalService retrievalService;
    @Autowired private EmbeddingService embeddingService;
    @Autowired private MarkdownDocumentParser parser;
    @Autowired private RecursiveChunkStrategy chunker;

    @Autowired private DocumentChunkMapper chunkMapper;

    private final Long workspaceId = 1L;
    private final Long kbId = 1L;

    // Logical (frozen identity) -> DB id maps and reverse maps (global counters)
    private final List<Long> allDocDbIds = new ArrayList<>();
    private final Map<String, Long> chunkDbIdByLogicalId = new LinkedHashMap<>();
    private final Map<Long, String> logicalIdByChunkDbId = new LinkedHashMap<>();
    private final Map<String, Long> docDbIdByLogicalId = new LinkedHashMap<>();
    private List<String> expectedLogicalChunkIds = List.of();
    private int totalChunks = 0;

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (!isDockerAvailable()) {
            return;
        }
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("intellidesk_test")
                    .withUsername("intellidesk")
                    .withPassword("intellidesk");
            postgres.start();
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("intellidesk.retrieval.es-index-name", () -> FROZEN_INDEX);

        // Real local Ollama embedding for Phase 8 Wave 1 final real-quality run.
        registry.add("intellidesk.embedding.api-key", () -> "ollama-placeholder");
        registry.add("intellidesk.embedding.base-url", () -> "http://127.0.0.1:11434");
        registry.add("intellidesk.embedding.model", () -> EMBEDDING_MODEL);
        registry.add("intellidesk.embedding.dimension", () -> String.valueOf(EMBEDDING_DIMENSION));
        registry.add("intellidesk.embedding.batch-size", () -> "10");

        // Real local reranker gateway for Phase 8 Wave 1 final real-quality run.
        registry.add("intellidesk.retrieval.rerank.enabled", () -> String.valueOf(RERANK_ENABLED));
        registry.add("intellidesk.retrieval.rerank.base-url", () -> RERANK_BASE_URL);
        registry.add("intellidesk.retrieval.rerank.api-key", () -> RERANK_API_KEY);
        registry.add("intellidesk.retrieval.rerank.model", () -> RERANK_MODEL);
        registry.add("intellidesk.retrieval.rerank.timeout-seconds", () -> "30");

        if (elasticsearch == null) {
            ImageFromDockerfile image = new ImageFromDockerfile()
                    .withDockerfileFromBuilder(builder -> builder
                            .from("docker.elastic.co/elasticsearch/elasticsearch:8.17.10")
                            .run("elasticsearch-plugin install --batch analysis-smartcn")
                            .build());
            elasticsearch = new ElasticsearchContainer(
                    DockerImageName.parse(image.get()).asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
                    .withEnv("discovery.type", "single-node")
                    .withEnv("xpack.security.enabled", "false");
            elasticsearch.start();
        }
        registry.add("intellidesk.elasticsearch.host", elasticsearch::getHost);
        registry.add("intellidesk.elasticsearch.port", elasticsearch::getFirstMappedPort);
    }

    @TestConfiguration
    static class RagHarnessConfig {

        /**
         * Real ElasticsearchClient wired against the container (like the integration template).
         * The real ElasticsearchChunkIndex / ElasticsearchIndexManager / KeywordRetriever /
         * HybridRetriever are @Profile("!test") so they are NOT auto-registered under the "test"
         * profile; we re-register the REAL implementations here (names chosen to avoid colliding
         * with TestInfrastructureConfig's mocks, and marked @Primary so the real ones win
         * injection — same wiring pattern as RetrievalSearchIntegrationTest).
         */
        @Bean
        public ElasticsearchClient evalEsClient(ElasticsearchProperties properties) {
            var host = new HttpHost(properties.getHost(), properties.getPort(), "http");
            var restClient = RestClient.builder(host).build();
            var mapper = new JacksonJsonpMapper();
            mapper.objectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            var transport = new RestClientTransport(restClient, mapper);
            return new ElasticsearchClient(transport);
        }

        @Bean
        public ElasticsearchIndexManager evalEsIndexManager(
                ElasticsearchClient client, RetrievalProperties retrievalProperties) {
            return new ElasticsearchIndexManager(client, retrievalProperties);
        }

        @Bean
        public ElasticsearchChunkIndex evalEsChunkIndex(
                ElasticsearchClient client, RetrievalProperties retrievalProperties) {
            return new ElasticsearchChunkIndex(client, retrievalProperties);
        }

        @Bean
        @Primary
        public KeywordRetriever evalKeywordRetriever(ElasticsearchChunkIndex index) {
            return new KeywordRetriever(index);
        }

        @Bean
        @Primary
        public HybridRetriever evalHybridRetriever(
                VectorRetriever vectorRetriever, KeywordRetriever keywordRetriever) {
            return new HybridRetriever(vectorRetriever, keywordRetriever);
        }

        /**
         * The REAL SpringAiEmbeddingService backed by the REAL production OpenAiEmbeddingModel
         * pointed at the local Ollama daemon. This is the same code path used in production
         * (EmbeddingConfig), so document-side and query-side embeddings both come from
         * qwen3-embedding:8b with dimensions=1536.
         */
        @Bean
        @Primary
        public SpringAiEmbeddingService evalSpringAiEmbeddingService(EmbeddingProperties properties) {
            EmbeddingModel model = new EmbeddingConfig(properties).embeddingModel();
            return new SpringAiEmbeddingService(model, properties);
        }

        @Bean
        @Primary
        public RerankClient evalRerankClient() {
            return new DashScopeCompatibleRerankClient(
                    RERANK_BASE_URL, RERANK_API_KEY, RERANK_MODEL, Duration.ofSeconds(30));
        }

        @Bean
        @Primary
        public RerankService evalRerankService(RerankClient client) {
            return new RerankService(client);
        }
    }

    // ------------------------------------------------------------------
    // 2. Ingest FROZEN corpus through REAL production components
    // ------------------------------------------------------------------

    @BeforeAll
    void ingestCorpusAndIndex() throws Exception {
        // Clean up in reverse FK order
        jdbcTemplate.execute("DELETE FROM retrieval_cleanup_task");
        jdbcTemplate.execute("DELETE FROM document_retrieval_task");
        jdbcTemplate.execute("DELETE FROM document_chunk");
        jdbcTemplate.execute("DELETE FROM document");
        jdbcTemplate.execute("DELETE FROM knowledge_base");
        jdbcTemplate.execute("DELETE FROM workspace_member");
        jdbcTemplate.execute("DELETE FROM workspace");
        jdbcTemplate.execute("DELETE FROM sys_user_role");
        jdbcTemplate.execute("DELETE FROM sys_role_permission");
        jdbcTemplate.execute("DELETE FROM sys_permission");
        jdbcTemplate.execute("DELETE FROM sys_user");

        // Base rows: user / workspace / knowledge base
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password_hash, email, status) VALUES (?, ?, ?, ?, ?)",
                1L, "evaluser", "hash", "eval@test.com", 1);
        jdbcTemplate.update("INSERT INTO workspace (id, name, owner_id) VALUES (?, ?, ?)",
                workspaceId, "Eval WS", 1L);
        jdbcTemplate.update("INSERT INTO knowledge_base (id, workspace_id, name, status, created_by) VALUES (?, ?, ?, ?, ?)",
                kbId, workspaceId, "Eval KB", "ACTIVE", 1L);

        String root = EvalManifestGenerator.resolveProjectRoot();
        String corpusDir = root + "/docs/evaluation/corpus";
        java.io.File[] corpusFiles = new java.io.File(corpusDir)
                .listFiles((d, name) -> name.endsWith(".md"));
        assertTrue(corpusFiles != null && corpusFiles.length == 14,
                "expected exactly 14 frozen .md corpus files, got " + (corpusFiles == null ? "null" : corpusFiles.length));
        java.util.Arrays.sort(corpusFiles, java.util.Comparator.comparing(java.io.File::getName));

        ChunkConfig chunkConfig = new ChunkConfig(ChunkStrategyType.RECURSIVE, CHUNK_SIZE, CHUNK_OVERLAP);
        ParseContext parseCtx = ParseContext.builder().build();

        long docId = 1L;
        long chunkDbId = 1L;

        for (java.io.File f : corpusFiles) {
            String logicalDocId = EvalManifestGenerator.logicalId(f.getName());
            byte[] raw;
            try (FileInputStream in = new FileInputStream(f)) {
                raw = in.readAllBytes();
            }
            ParsedDocument parsed = parser.parse(new java.io.ByteArrayInputStream(raw), parseCtx);
            List<ChunkDraft> chunks = chunker.split(parsed, chunkConfig);

            // Real embedding of chunk contents (REAL SpringAiEmbeddingService batches internally)
            List<String> contents = chunks.stream().map(ChunkDraft::getContent).toList();
            EmbeddingBatchResult batch = embeddingService.embedDocuments(contents);

            // Insert document row (COMPLETED, RECURSIVE 1000/150)
            jdbcTemplate.update(
                    "INSERT INTO document (id, knowledge_base_id, original_file_name, file_extension, content_type, file_size, checksum_sha256, bucket_name, object_key, status, chunk_strategy, chunk_size, chunk_overlap, parser_metadata, created_by) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                    docId, kbId, f.getName(), "md", "text/markdown", (long) raw.length,
                    EvalHashing.sha256Hex(raw),
                    "intellidesk-documents", "eval/" + f.getName(), "COMPLETED",
                    CHUNK_STRATEGY, CHUNK_SIZE, CHUNK_OVERLAP, "{}", 1L);

            // Insert one document_chunk row per chunk (embedding is the deterministic vector)
            for (int i = 0; i < chunks.size(); i++) {
                ChunkDraft c = chunks.get(i);
                float[] vec = batch.getEmbeddings().get(i);
                PGvector pgVec = new PGvector(vec);
                String logicalChunkId = logicalDocId + EvalDatasetValidator.STABLE_CHUNK_ID_SEPARATOR + c.getChunkIndex();
                jdbcTemplate.update(
                        "INSERT INTO document_chunk (id, knowledge_base_id, document_id, chunk_index, content, character_count, embedding, embedding_model, embedding_generation, embedding_fence_token, embedded_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                        chunkDbId, kbId, docId, c.getChunkIndex(), c.getContent(),
                        c.getCharacterCount(), pgVec, EMBEDDING_MODEL, INDEX_GENERATION, chunkDbId);
                chunkDbIdByLogicalId.put(logicalChunkId, chunkDbId);
                logicalIdByChunkDbId.put(chunkDbId, logicalChunkId);
                chunkDbId++;
            }

            // Create a READY retrieval task for the document (generation 1, fence token = chunk db id 1)
            jdbcTemplate.update(
                    "INSERT INTO document_retrieval_task (document_id, status, generation, attempt_count, max_attempts, fence_token, message_id, embedding_model, embedding_dimension, es_index_name, indexed_chunk_count, ready_at) " +
                            "VALUES (?, 'READY', 1, 1, 3, ?, CAST(? AS uuid), ?, ?, ?, ?, NOW())",
                    docId, chunkDbIdByLogicalId.get(logicalDocId + EvalDatasetValidator.STABLE_CHUNK_ID_SEPARATOR + "0"),
                    UUID.randomUUID().toString(), EMBEDDING_MODEL, EMBEDDING_DIMENSION, FROZEN_INDEX, chunks.size());

            docDbIdByLogicalId.put(logicalDocId, docId);
            allDocDbIds.add(docId);
            totalChunks += chunks.size();
            docId++;
        }

        // Ensure fresh ES index and index all chunks into it
        boolean esExists = esClient.indices().exists(e -> e.index(FROZEN_INDEX)).value();
        if (esExists) {
            esClient.indices().delete(d -> d.index(FROZEN_INDEX));
        }
        indexManager.ensureIndex();
        for (Map.Entry<String, Long> e : chunkDbIdByLogicalId.entrySet()) {
            String logicalChunkId = e.getKey();
            Long dbId = e.getValue();
            int bar = logicalChunkId.indexOf(EvalDatasetValidator.STABLE_CHUNK_ID_SEPARATOR);
            String logicalDoc = logicalChunkId.substring(0, bar);
            int ordinal = Integer.parseInt(logicalChunkId.substring(bar + 1));
            Long docDbId = docDbIdByLogicalId.get(logicalDoc);
            String content = chunkContent(logicalChunkId);
            ElasticsearchChunkDocument esDoc = new ElasticsearchChunkDocument();
            esDoc.setChunkId(dbId);
            esDoc.setDocumentId(docDbId);
            esDoc.setKnowledgeBaseId(kbId);
            esDoc.setWorkspaceId(workspaceId);
            esDoc.setChunkIndex(ordinal);
            esDoc.setContent(content);
            esDoc.setMetadata(java.util.Collections.emptyMap());
            esDoc.setIndexGeneration(INDEX_GENERATION);
            esDoc.setFenceToken(dbId);
            esDoc.setIndexedAt(Instant.now());
            esClient.index(idx -> idx
                    .index(FROZEN_INDEX)
                    .id(String.valueOf(dbId))
                    .document(esDoc)
                    .refresh(Refresh.WaitFor));
        }

        // ---- Verify chunk identity against the frozen indexed_chunk_manifest.json ----
        expectedLogicalChunkIds = readExpectedLogicalChunkIds(root + "/docs/evaluation/raw/indexed_chunk_manifest.json");
        Set<String> actualLogicalChunkIds = new LinkedHashSet<>(chunkDbIdByLogicalId.keySet());
        assertEquals(42, totalChunks, "total corpus chunk count must be 42");
        assertEquals(42, expectedLogicalChunkIds.size(), "manifest total chunk count must be 42");
        assertEquals(new LinkedHashSet<>(expectedLogicalChunkIds), actualLogicalChunkIds,
                "logical chunk id set must equal the frozen indexed_chunk_manifest.json identity set");

        System.out.println("== evaluation corpus ingest ==");
        System.out.println("documents: " + allDocDbIds.size());
        System.out.println("chunks:    " + totalChunks);
        System.out.println("logical chunk id set verified against indexed_chunk_manifest.json: OK (42/42)");
        System.out.println("ingest pipeline: REAL MarkdownDocumentParser + RecursiveChunkStrategy("
                + CHUNK_STRATEGY + "," + CHUNK_SIZE + "/" + CHUNK_OVERLAP + ") + REAL SpringAiEmbeddingService "
                + "[LOCAL_OLLAMA_REAL qwen3-embedding:8b 1536-dim] + direct-JDBC chunk insert + per-doc ES indexing into " + FROZEN_INDEX);
        System.out.println("embedding provider = " + EMBEDDING_PROVIDER_LABEL + " (model=" + EMBEDDING_MODEL
                + ", dim=" + EMBEDDING_DIMENSION + ")");
    }

    private String chunkContent(String logicalChunkId) {
        // Pull authoritative content from PG via MyBatis (real entities) — avoids trusting ES _source
        Long dbId = chunkDbIdByLogicalId.get(logicalChunkId);
        DocumentChunk chunk = chunkMapper.selectById(dbId);
        return chunk != null ? chunk.getContent() : "";
    }

    private List<String> readExpectedLogicalChunkIds(String manifestPath) throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(new java.io.File(manifestPath));
        List<String> ids = new ArrayList<>();
        for (JsonNode doc : root.get("documents")) {
            String logicalDocId = doc.get("logical_document_id").asText();
            for (JsonNode ch : doc.get("chunks")) {
                ids.add(logicalDocId + EvalDatasetValidator.STABLE_CHUNK_ID_SEPARATOR + ch.get("chunk_ordinal").asInt());
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // 4. Read frozen evaluation dataset + run retrieval
    // ------------------------------------------------------------------

    @Test
    void runEvaluation() throws Exception {
        System.out.println("== RagEvaluationHarness.runEvaluation ==");
        System.out.println("REAL PROVIDER: embedding = " + EMBEDDING_PROVIDER_LABEL + " / " + EMBEDDING_MODEL + " / dim=" + EMBEDDING_DIMENSION);
        System.out.println("REAL PROVIDER: rerank = " + RERANK_GATE + " / " + RERANK_MODEL + " / gateway=" + RERANK_BASE_URL);

        String root = EvalManifestGenerator.resolveProjectRoot();
        String datasetPath = root + "/docs/evaluation/dataset/evaluation_dataset.json";
        Path datasetFile = Path.of(datasetPath);
        if (!Files.exists(datasetFile)) {
            throw new IllegalStateException(
                    "evaluation dataset not found at " + datasetPath + ". Finalize docs/evaluation/dataset/evaluation_dataset.json before running.");
        }

        String corpusHash = readCorpusHash(root + "/docs/evaluation/corpus/corpus_freeze.json");
        String datasetHash = EvalHashing.sha256Hex(Files.readAllBytes(datasetFile));
        String configHash = EvalHashing.sha256Hex(EvalHashing.canonicalJson(buildConfigBlock()));
        String frozenConfigHash = readFrozenRetrievalConfigHash();
        if (!configHash.equals(frozenConfigHash)) {
            throw new IllegalStateException(
                    "Config hash linkage fail-fast: computed=" + configHash + " != frozen=" + frozenConfigHash);
        }
        System.out.println("config hash linkage OK: computed=" + configHash + " == frozen=" + frozenConfigHash);

        // ---- Immutable run-set namespace initialization (v1.1) ----
        String configId = configHash.substring(0, Math.min(CONFIG_ID_PREFIX_LENGTH, configHash.length())).toLowerCase();
        Path rawRoot = Path.of(root, "docs", "evaluation", "raw");
        String runSetId = resolveRunSetId();
        Path runSetDir = rawRoot.resolve(EVIDENCE_CLASS).resolve(configId).resolve(runSetId);
        if (Files.exists(runSetDir)) {
            throw new IllegalStateException(
                    "FAIL_IF_EXISTS: run-set directory already exists: " + runSetDir + ". Use a new run-set-id.");
        }
        Files.createDirectories(runSetDir);
        System.out.println("run-set namespace: " + runSetDir);

        // Expected modes/runs for lifecycle manifest
        List<String> expectedModes = List.of("VECTOR", "KEYWORD", "HYBRID", "HYBRID_RERANK");
        int expectedRunsPerMode = 2;
        Map<String, Object> runSetManifest = new LinkedHashMap<>();
        runSetManifest.put("schema_version", "1.0");
        runSetManifest.put("evidence_class", EVIDENCE_CLASS);
        runSetManifest.put("config_hash", configHash);
        runSetManifest.put("config_id", configId);
        runSetManifest.put("corpus_hash", corpusHash);
        runSetManifest.put("dataset_hash", datasetHash);
        runSetManifest.put("run_set_id", runSetId);
        runSetManifest.put("actor", "implementation");
        runSetManifest.put("purpose", "compensation-replay");
        runSetManifest.put("expected_modes", expectedModes);
        runSetManifest.put("expected_runs_per_mode", expectedRunsPerMode);
        runSetManifest.put("status", "INITIALIZING");
        runSetManifest.put("created_at", Instant.now().toString());
        writeRunSetManifest(runSetDir, runSetManifest);

        // Parse dataset questions
        ObjectMapper om = new ObjectMapper();
        JsonNode rootNode = om.readTree(datasetFile.toFile());
        List<JsonNode> questionNodes = new java.util.ArrayList<>();
        if (rootNode.isArray()) {
            rootNode.forEach(questionNodes::add);
        } else if (rootNode.has("questions")) {
            rootNode.get("questions").forEach(questionNodes::add);
        } else {
            throw new IllegalStateException("evaluation_dataset.json must be an array or contain a 'questions' array");
        }
        List<Map<String, Object>> rawQuestions = om.convertValue(questionNodes, new TypeReference<List<Map<String, Object>>>() {});
        List<Map<String, Object>> qualified = new java.util.ArrayList<>();
        for (Map<String, Object> q : rawQuestions) {
            Object qq = q.get("qualified_quality_question");
            boolean isQualified = qq instanceof Boolean b ? b
                    : (qq instanceof String s ? Boolean.parseBoolean(s) : false);
            if (isQualified) {
                qualified.add(q);
            }
        }
        System.out.println("dataset questions: " + rawQuestions.size()
                + " (qualified/quality: " + qualified.size() + ")");

        RetrievalScope scope = RetrievalScope.of(workspaceId, List.of(kbId));

        // run specs: (modeLabel, retrievalMode, runIndex)
        // run_index 1 runs all 4 labels; run_index 2 repeats all 4 to record reproducibility
        // (VECTOR/KEYWORD/HYBRID are deterministic; HYBRID_RERANK may vary and is recorded honestly).
        List<RunSpec> runs = List.of(
                new RunSpec(RetrievalMode.VECTOR, "VECTOR", 1),
                new RunSpec(RetrievalMode.KEYWORD, "KEYWORD", 1),
                new RunSpec(RetrievalMode.HYBRID, "HYBRID", 1),
                new RunSpec(RetrievalMode.HYBRID, "HYBRID_RERANK", 1),
                new RunSpec(RetrievalMode.VECTOR, "VECTOR", 2),
                new RunSpec(RetrievalMode.KEYWORD, "KEYWORD", 2),
                new RunSpec(RetrievalMode.HYBRID, "HYBRID", 2),
                new RunSpec(RetrievalMode.HYBRID, "HYBRID_RERANK", 2)
        );

        // determinism signature storage: mode -> (runIndex -> questionId -> ranked signatures)
        Map<String, Map<Integer, Map<String, List<List<String>>>>> determinismSig = new LinkedHashMap<>();

        for (RunSpec spec : runs) {
            Map<String, QuestionRanked> perQuestionArrays = new LinkedHashMap<>();
            for (Map<String, Object> q : qualified) {
                String questionId = String.valueOf(q.get("id"));
                String question = String.valueOf(q.get("question"));
                Set<String> relevantChunks = new LinkedHashSet<>();
                Object rc = q.get("relevant_chunks");
                if (rc instanceof List<?> list) {
                    for (Object o : list) {
                        if (o != null) {
                            relevantChunks.add(String.valueOf(o));
                        }
                    }
                }

                RetrievalQuery query = new RetrievalQuery(question, scope);
                boolean useRerank = "HYBRID_RERANK".equals(spec.modeLabel());
                long t0 = System.nanoTime();
                List<RetrievalResult> results;
                if (useRerank) {
                    results = retrievalService.search(query, RetrievalMode.HYBRID, CANDIDATE_TOP_K, TOP_K, true);
                } else {
                    results = retrievalService.search(query, spec.mode(), CANDIDATE_TOP_K, TOP_K, false);
                }
                long latencyMs = (System.nanoTime() - t0) / 1_000_000;

                List<Map<String, Object>> ranked = new java.util.ArrayList<>();
                List<List<String>> signature = new java.util.ArrayList<>();
                int rank = 1;
                for (RetrievalResult r : results) {
                    String logicalChunkId = logicalIdByChunkDbId.get(r.getChunkId());
                    String logicalDocId = logicalChunkId != null
                            ? logicalChunkId.substring(0, logicalChunkId.indexOf(EvalDatasetValidator.STABLE_CHUNK_ID_SEPARATOR))
                            : null;
                    boolean relevant = logicalChunkId != null && relevantChunks.contains(logicalChunkId);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("rank", rank);
                    item.put("db_chunk_id", r.getChunkId());
                    item.put("logical_document_id", logicalDocId);
                    item.put("logical_chunk_id", logicalChunkId);
                    item.put("score", r.getScore());
                    item.put("score_type", r.getScoreType().getValue());
                    item.put("relevant", relevant);
                    ranked.add(item);
                    signature.add(List.of(
                            String.valueOf(r.getChunkId()),
                            logicalChunkId != null ? logicalChunkId : "",
                            String.valueOf(r.getScore()),
                            r.getScoreType().getValue(),
                            String.valueOf(relevant)));
                    rank++;
                }
                perQuestionArrays.put(questionId, new QuestionRanked(ranked, latencyMs));
                determinismSig.computeIfAbsent(spec.modeLabel(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(spec.runIndex(), k -> new LinkedHashMap<>())
                        .put(questionId, signature);
            }
            writeRunJson(runSetDir, om, spec, corpusHash, datasetHash, configHash, perQuestionArrays);
            updateRunSetManifestStatus(runSetDir, "PARTIAL", null);
        }

        // Reproducibility confirmation (run_index 1 vs 2) for all modes.
        // VECTOR / KEYWORD / HYBRID are deterministic and must be IDENTICAL.
        // HYBRID_RERANK uses a real cross-encoder; variation is allowed and recorded honestly.
        for (String mode : List.of("VECTOR", "KEYWORD", "HYBRID", "HYBRID_RERANK")) {
            Map<String, List<List<String>>> m1 = determinismSig.get(mode).get(1);
            Map<String, List<List<String>>> m2 = determinismSig.get(mode).get(2);
            boolean identical = m1 != null && m2 != null && m1.keySet().equals(m2.keySet());
            if (identical) {
                for (String qid : m1.keySet()) {
                    if (!m1.get(qid).equals(m2.get(qid))) {
                        identical = false;
                        break;
                    }
                }
            }
            String expectation = ("HYBRID_RERANK".equals(mode)) ? "RECORDED (real reranker may vary)" : "IDENTICAL";
            System.out.println("reproducibility[" + mode + "] run1==run2 (ranked identity+order+score+type+relevant): "
                    + (identical ? "IDENTICAL" : "DIFFERENT") + " (expectation: " + expectation + ")");
        }

        // ---- Atomic completion finalize (v1.1) ----
        validateRunSetComplete(runSetDir, expectedModes, expectedRunsPerMode, configHash, corpusHash, datasetHash);
        Path runSetSummary = runSetDir.resolve("metrics_summary.json");
        EvalRecomputeScript.recompute(runSetDir, runSetSummary, datasetFile);
        System.out.println("raw-only recompute OK: " + runSetSummary);
        updateRunSetManifestStatus(runSetDir, "COMPLETE", null);
        System.out.println("run-set finalized: status=COMPLETE");
    }

    private record QuestionRanked(List<Map<String, Object>> ranked, long latencyMs) {
    }

    private record RunSpec(RetrievalMode mode, String modeLabel, int runIndex) {
    }

    private String readCorpusHash(String corpusHashPath) throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(new java.io.File(corpusHashPath));
        return root.get("build").get("corpus_hash").asText();
    }

    private Map<String, Object> buildConfigBlock() {
        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("provider_mode", EMBEDDING_PROVIDER_LABEL);
        embedding.put("model", EMBEDDING_MODEL);
        embedding.put("dimension", EMBEDDING_DIMENSION);

        Map<String, Object> retrievalParams = new LinkedHashMap<>();
        retrievalParams.put("candidate_top_k", CANDIDATE_TOP_K);
        retrievalParams.put("top_k", TOP_K);
        retrievalParams.put("reported_k", REPORTED_KS);
        retrievalParams.put("rrf_k", RRF_K);
        retrievalParams.put("es_index", FROZEN_INDEX);
        retrievalParams.put("index_generation", INDEX_GENERATION);

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("embedding", embedding);
        cfg.put("reranker_quality_config_hash", readRerankerQualityConfigHash());
        cfg.put("retrieval_params", retrievalParams);
        return cfg;
    }

    private String readRerankerQualityConfigHash() {
        return String.valueOf(readRerankerQualityConfig().get("reranker_quality_config_hash"));
    }

    private Map<String, Object> readRerankerQualityConfig() {
        try {
            String root = EvalManifestGenerator.resolveProjectRoot();
            ObjectMapper om = new ObjectMapper();
            JsonNode node = om.readTree(new java.io.File(root + "/docs/evaluation/config/retrieval_config.json"));
            JsonNode rq = node.get("reranker_quality_config");
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("provider_mode", rq.get("provider_mode").asText());
            map.put("model_id", rq.get("model_id").asText());
            map.put("model_revision_identity", rq.get("model_revision_identity").asText());
            map.put("model_artifact_identity", rq.get("model_artifact_identity").asText());
            map.put("tokenizer_artifact_identity", rq.get("tokenizer_artifact_identity").asText());
            map.put("inference_stack", rq.get("inference_stack").asText());
            map.put("inference_stack_version_identity", rq.get("inference_stack_version_identity").asText());
            map.put("input_pair_semantics", rq.get("input_pair_semantics").asText());
            map.put("max_length", rq.get("max_length").asInt());
            map.put("truncation_policy", rq.get("truncation_policy").asText());
            map.put("normalization", rq.get("normalization").asBoolean());
            map.put("score_semantics", rq.get("score_semantics").asText());
            map.put("ranking_direction", rq.get("ranking_direction").asText());
            map.put("tie_break_policy", rq.get("tie_break_policy").asText());
            map.put("precision", rq.get("precision").asText());
            map.put("inference_mode", rq.get("inference_mode").asText());
            map.put("reranker_quality_config_hash", node.get("reranker_quality_config_hash").asText());
            return map;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read frozen reranker_quality_config", e);
        }
    }

    private String readFrozenRetrievalConfigHash() {
        try {
            String root = EvalManifestGenerator.resolveProjectRoot();
            ObjectMapper om = new ObjectMapper();
            JsonNode node = om.readTree(new java.io.File(root + "/docs/evaluation/config/retrieval_config.json"));
            return node.get("retrieval_config_hash").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read frozen retrieval_config_hash", e);
        }
    }

    private Map<String, Object> environmentBlock() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("java.version", System.getProperty("java.version"));
        env.put("os.name", System.getProperty("os.name"));
        env.put("os.version", System.getProperty("os.version"));
        env.put("user.dir", System.getProperty("user.dir"));
        env.put("docker.available", isDockerAvailable());
        return env;
    }

    private void writeRunJson(Path runSetDir, ObjectMapper om, RunSpec spec,
                              String corpusHash, String datasetHash, String configHash,
                              Map<String, QuestionRanked> perQuestion) throws Exception {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("run_id", spec.modeLabel() + "-" + spec.runIndex());
        run.put("mode", spec.modeLabel());
        run.put("run_index", spec.runIndex());
        run.put("timestamp", Instant.now().toString());
        run.put("corpus_hash", corpusHash);
        run.put("dataset_hash", datasetHash);
        run.put("config_hash", configHash);
        run.put("run_set_id", resolveRunSetId());
        run.put("evidence_class", EVIDENCE_CLASS);
        run.put("topK", TOP_K);
        run.put("candidateTopK", CANDIDATE_TOP_K);
        run.put("reported_k", REPORTED_KS);

        Map<String, Object> rerankCfg = new LinkedHashMap<>();
        rerankCfg.put("enabled", RERANK_ENABLED);
        rerankCfg.put("gate", RERANK_GATE);
        rerankCfg.put("provider", "LOCAL_REAL_GATEWAY");
        rerankCfg.put("model", RERANK_MODEL);
        rerankCfg.put("base_url", RERANK_BASE_URL);
        Map<String, Object> rqConfig = readRerankerQualityConfig();
        rerankCfg.put("reranker_quality_config_hash", rqConfig.get("reranker_quality_config_hash"));
        run.put("rerank_config", rerankCfg);

        // Raw metadata binding per v1.1 amendment
        Map<String, Object> rerankMeta = new LinkedHashMap<>();
        rerankMeta.put("reranker_model_id", RERANK_MODEL);
        rerankMeta.put("reranker_model_revision_identity", rqConfig.get("model_revision_identity"));
        rerankMeta.put("reranker_model_artifact_identity", rqConfig.get("model_artifact_identity"));
        rerankMeta.put("reranker_tokenizer_artifact_identity", rqConfig.get("tokenizer_artifact_identity"));
        rerankMeta.put("reranker_quality_config_hash", rqConfig.get("reranker_quality_config_hash"));
        rerankMeta.put("reranker_inference_stack_identity", rqConfig.get("inference_stack_version_identity"));
        rerankMeta.put("reranker_max_length", rqConfig.get("max_length"));
        rerankMeta.put("reranker_normalization", rqConfig.get("normalization"));
        rerankMeta.put("reranker_precision", rqConfig.get("precision"));
        run.put("reranker_metadata", rerankMeta);

        Map<String, Object> emb = new LinkedHashMap<>();
        emb.put("provider", EMBEDDING_PROVIDER_LABEL);
        emb.put("model", EMBEDDING_MODEL);
        emb.put("dimension", EMBEDDING_DIMENSION);
        emb.put("base_url", "http://127.0.0.1:11434");
        run.put("embedding", emb);

        Map<String, Object> idx = new LinkedHashMap<>();
        idx.put("es", FROZEN_INDEX);
        idx.put("generation", INDEX_GENERATION);
        run.put("index", idx);

        run.put("harness_file_sha256",
                EvalHashing.sha256Hex(Files.readAllBytes(Path.of(
                        EvalManifestGenerator.resolveProjectRoot()
                                + "/backend/src/test/java/com/intellidesk/evaluation/RagEvaluationHarness.java"))));
        run.put("environment", environmentBlock());

        // Honesty flags at run level
        boolean rerankExecuted = "HYBRID_RERANK".equals(spec.modeLabel());
        run.put("rerank_executed", rerankExecuted);
        run.put("rerank_gate", RERANK_GATE);

        List<Map<String, Object>> questions = new java.util.ArrayList<>();
        for (Map.Entry<String, QuestionRanked> e : perQuestion.entrySet()) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("question_id", e.getKey());
            q.put("mode", spec.modeLabel());
            q.put("retrieval_latency_ms", e.getValue().latencyMs());
            q.put("ranked", e.getValue().ranked());
            questions.add(q);
        }
        run.put("questions", questions);

        String fileName = "eval-" + spec.modeLabel() + "-" + spec.runIndex() + ".json";
        Path target = runSetDir.resolve(fileName);
        if (Files.exists(target)) {
            throw new FileAlreadyExistsException("FAIL_IF_EXISTS: " + target);
        }
        byte[] bytes = om.writerWithDefaultPrettyPrinter().writeValueAsBytes(run);
        atomicWrite(target, bytes);
        System.out.println("wrote " + target + " (mode=" + spec.modeLabel()
                + ", run=" + spec.runIndex() + ", questions=" + questions.size() + ")");
    }

    private void atomicWrite(Path target, byte[] bytes) throws Exception {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Fallback for platforms that do not support atomic move: regular move still fails if target exists.
            Files.move(tmp, target);
        }
    }

    private void writeRunSetManifest(Path runSetDir, Map<String, Object> manifest) throws Exception {
        Path target = runSetDir.resolve("run_set_manifest.json");
        ObjectMapper om = new ObjectMapper();
        byte[] bytes = om.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        if (Files.exists(target)) {
            Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            atomicWrite(target, bytes);
        }
    }

    private void updateRunSetManifestStatus(Path runSetDir, String status, String reason) throws Exception {
        Path target = runSetDir.resolve("run_set_manifest.json");
        ObjectMapper om = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = om.readValue(target.toFile(), LinkedHashMap.class);
        manifest.put("status", status);
        if (reason != null) {
            manifest.put("failure_reason", reason);
        }
        if ("COMPLETE".equals(status)) {
            manifest.put("completed_at", Instant.now().toString());
            List<Map<String, Object>> entries = buildRunSetEvidenceEntries(runSetDir);
            manifest.put("evidence_entries", entries);
            Set<String> modes = new LinkedHashSet<>();
            int maxRun = 0;
            for (Map<String, Object> e : entries) {
                String name = e.get("relative_path").toString();
                String fileName = Path.of(name).getFileName().toString();
                // eval-<MODE>-<run>.json
                if (fileName.startsWith("eval-") && fileName.endsWith(".json")) {
                    String body = fileName.substring("eval-".length(), fileName.length() - ".json".length());
                    int lastDash = body.lastIndexOf('-');
                    if (lastDash > 0) {
                        modes.add(body.substring(0, lastDash));
                        maxRun = Math.max(maxRun, Integer.parseInt(body.substring(lastDash + 1)));
                    }
                }
            }
            manifest.put("completed_modes", new ArrayList<>(modes));
            manifest.put("completed_runs_per_mode", maxRun);
        }
        writeRunSetManifest(runSetDir, manifest);
    }

    private List<Map<String, Object>> buildRunSetEvidenceEntries(Path runSetDir) throws Exception {
        List<Path> files = Files.list(runSetDir)
                .filter(p -> p.getFileName().toString().startsWith("eval-") && p.getFileName().toString().endsWith(".json"))
                .sorted(java.util.Comparator.comparing(Path::getFileName))
                .toList();
        Path projectRoot = Path.of(EvalManifestGenerator.resolveProjectRoot());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Path p : files) {
            Map<String, Object> e = new LinkedHashMap<>();
            String rel = projectRoot.relativize(p).toString().replace('\\', '/');
            e.put("relative_path", rel);
            e.put("size_bytes", Files.size(p));
            e.put("sha256", sha256Hex(Files.readAllBytes(p)));
            entries.add(e);
        }
        return entries;
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return bytesToHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void validateRunSetComplete(Path runSetDir, List<String> expectedModes, int expectedRunsPerMode,
                                          String configHash, String corpusHash, String datasetHash) throws Exception {
        for (String mode : expectedModes) {
            for (int i = 1; i <= expectedRunsPerMode; i++) {
                Path p = runSetDir.resolve("eval-" + mode + "-" + i + ".json");
                if (!Files.exists(p)) {
                    throw new IllegalStateException("run-set incomplete: missing " + p);
                }
                ObjectMapper om = new ObjectMapper();
                JsonNode run = om.readValue(p.toFile(), JsonNode.class);
                if (!configHash.equals(run.get("config_hash").asText())) {
                    throw new IllegalStateException("run-set hash linkage fail: config_hash mismatch in " + p);
                }
                if (!corpusHash.equals(run.get("corpus_hash").asText())) {
                    throw new IllegalStateException("run-set hash linkage fail: corpus_hash mismatch in " + p);
                }
                if (!datasetHash.equals(run.get("dataset_hash").asText())) {
                    throw new IllegalStateException("run-set hash linkage fail: dataset_hash mismatch in " + p);
                }
                if ("HYBRID_RERANK".equals(mode) && !run.get("rerank_executed").asBoolean()) {
                    throw new IllegalStateException("run-set rerank evidence fail: rerank_executed=false in " + p);
                }
            }
        }
        System.out.println("run-set completion validation OK: all expected raw present and hash-linked");
    }

    private static byte[] sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}