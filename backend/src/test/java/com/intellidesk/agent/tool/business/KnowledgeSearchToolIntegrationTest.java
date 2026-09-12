package com.intellidesk.agent.tool.business;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.agent.tool.AgentToolExecutionResult;
import com.intellidesk.agent.tool.ToolExecutionContext;
import com.intellidesk.embedding.EmbeddingBatchResult;
import com.intellidesk.embedding.EmbeddingService;
import com.intellidesk.infrastructure.config.ElasticsearchProperties;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.retrieval.*;
import com.intellidesk.retrieval.fusion.HybridRetriever;
import com.intellidesk.retrieval.keyword.*;
import com.pgvector.PGvector;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
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
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 5 Wave 3 — Real KnowledgeSearchTool Retrieval Integration.
 * <p>
 * Uses real RetrievalService, RetrievalScopeResolver, KnowledgeSearchTool,
 * VectorRetriever, KeywordRetriever, HybridRetriever, RetrievalHydrator.
 * <p>
 * Real PostgreSQL + pgvector + Elasticsearch (Testcontainers).
 * No Mockito mock for RetrievalService or ScopeResolver.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestInfrastructureConfig.class, KnowledgeSearchToolIntegrationTest.TestRetrievalConfig.class})
@DisplayName("KnowledgeSearchTool Real Retrieval Integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("isDockerAvailable")
class KnowledgeSearchToolIntegrationTest {

    private static final String TEST_INDEX = "test-agent-tool-retrieval-v1";
    private static PostgreSQLContainer<?> postgres;
    private static ElasticsearchContainer elasticsearch;

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

        // PostgreSQL
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

        // Elasticsearch
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
        registry.add("intellidesk.retrieval.es-index-name", () -> TEST_INDEX);
    }

    @AfterAll
    void stopContainers() {
        if (elasticsearch != null) elasticsearch.stop();
        if (postgres != null) postgres.stop();
    }

    @TestConfiguration
    static class TestRetrievalConfig {

        @Bean
        public ElasticsearchClient elasticsearchClient(ElasticsearchProperties properties) {
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
        public ElasticsearchIndexManager elasticsearchIndexManager(
                ElasticsearchClient client, RetrievalProperties retrievalProperties) {
            return new ElasticsearchIndexManager(client, retrievalProperties);
        }

        @Bean
        public ElasticsearchChunkIndex elasticsearchChunkIndex(
                ElasticsearchClient client, RetrievalProperties retrievalProperties) {
            return new ElasticsearchChunkIndex(client, retrievalProperties);
        }

        @Bean
        @Primary
        public EmbeddingService mockEmbeddingService() {
            EmbeddingService mock = mock(EmbeddingService.class);
            when(mock.model()).thenReturn("text-embedding-3-small");
            when(mock.dimension()).thenReturn(1536);
            when(mock.embedQuery(any())).thenAnswer(inv -> {
                float[] vec = new float[1536];
                for (int i = 0; i < 1536; i++) vec[i] = 0.01f * (i % 100);
                return vec;
            });
            when(mock.embedDocuments(anyList())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                List<String> texts = (List<String>) inv.getArgument(0);
                List<float[]> embeddings = new ArrayList<>();
                for (int i = 0; i < texts.size(); i++) {
                    float[] v = new float[1536];
                    for (int j = 0; j < 1536; j++) v[j] = 0.01f * ((j + i) % 100);
                    embeddings.add(v);
                }
                return new EmbeddingBatchResult(embeddings, "text-embedding-3-small", 1536, 0);
            });
            return mock;
        }

        @Bean
        @Primary
        public KeywordRetriever keywordRetriever(ElasticsearchChunkIndex index) {
            return new KeywordRetriever(index);
        }

        @Bean
        @Primary
        public HybridRetriever hybridRetriever(VectorRetriever vectorRetriever, KeywordRetriever keywordRetriever) {
            return new HybridRetriever(vectorRetriever, keywordRetriever);
        }
    }

    // ================================================================
    // REAL beans (no Mockito)
    // ================================================================

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ElasticsearchClient esClient;
    @Autowired private ElasticsearchIndexManager indexManager;
    @Autowired private RetrievalService retrievalService;       // REAL
    @Autowired private RetrievalScopeResolver scopeResolver;     // REAL
    @Autowired private RetrievalHydrator hydrator;               // REAL

    private KnowledgeSearchTool tool; // REAL, wired manually with real services

    // ================================================================
    // Test data constants
    // ================================================================

    private static final Long WS_A = 1L;
    private static final Long WS_B = 2L;
    private static final Long USER_A = 1L;
    private static final Long KB_A = 1L;
    private static final Long KB_B = 2L;
    private static final Long DOC_A = 1L;
    private static final Long DOC_B = 2L;

    private ToolExecutionContext ctxA;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available");

        tool = new KnowledgeSearchTool(retrievalService, scopeResolver);
        ctxA = new ToolExecutionContext(USER_A, WS_A, 300L, "trace-tool-integration");

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

        // ---- Users ----
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password_hash, email, status) VALUES (?, ?, ?, ?, ?)",
                USER_A, "user-a", "hash", "a@test.com", 1);
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password_hash, email, status) VALUES (?, ?, ?, ?, ?)",
                2L, "user-b", "hash", "b@test.com", 1);

        // ---- Workspace A + member ----
        jdbcTemplate.update("INSERT INTO workspace (id, name, owner_id) VALUES (?, ?, ?)",
                WS_A, "Workspace A", USER_A);
        jdbcTemplate.update("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, ?)",
                WS_A, USER_A, "OWNER");

        // ---- Workspace B + member ----
        jdbcTemplate.update("INSERT INTO workspace (id, name, owner_id) VALUES (?, ?, ?)",
                WS_B, "Workspace B", 2L);
        jdbcTemplate.update("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, ?)",
                WS_B, 2L, "OWNER");

        // ---- KB A (in WS A) ----
        jdbcTemplate.update("INSERT INTO knowledge_base (id, workspace_id, name, status, created_by) VALUES (?, ?, ?, ?, ?)",
                KB_A, WS_A, "KB A", "ACTIVE", USER_A);

        // ---- KB B (in WS B) ----
        jdbcTemplate.update("INSERT INTO knowledge_base (id, workspace_id, name, status, created_by) VALUES (?, ?, ?, ?, ?)",
                KB_B, WS_B, "KB B", "ACTIVE", 2L);

        // ---- Document A (COMPLETED, in KB A) ----
        jdbcTemplate.update(
                "INSERT INTO document (id, knowledge_base_id, original_file_name, file_extension, content_type, file_size, checksum_sha256, bucket_name, object_key, status, chunk_strategy, chunk_size, chunk_overlap, parser_metadata, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                DOC_A, KB_A, "doc-a.pdf", "pdf", "application/pdf", 1024L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "intellidesk-documents", "key-a", "COMPLETED",
                "RECURSIVE", 1000, 150, "{}", USER_A);

        // ---- Document B (COMPLETED, in KB B) ----
        jdbcTemplate.update(
                "INSERT INTO document (id, knowledge_base_id, original_file_name, file_extension, content_type, file_size, checksum_sha256, bucket_name, object_key, status, chunk_strategy, chunk_size, chunk_overlap, parser_metadata, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                DOC_B, KB_B, "doc-b.pdf", "pdf", "application/pdf", 1024L,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "intellidesk-documents", "key-b", "COMPLETED",
                "RECURSIVE", 1000, 150, "{}", 2L);

        // ---- Chunks for Document A ----
        String[] contentsA = {
                "北京地区差旅住宿标准为每人每天500元。出差人员需提前申请并获得审批。",
                "上海地区差旅住宿标准为每人每天600元。餐饮补贴为每人每天200元。",
                "国际差旅标准根据目的地国家不同，分为A类、B类和C类三个等级。"
        };
        for (int i = 0; i < 3; i++) {
            float[] vec = new float[1536];
            for (int j = 0; j < 1536; j++) vec[j] = 0.01f * ((j + i) % 100);
            PGvector pgVec = new PGvector(vec);
            jdbcTemplate.update(
                    "INSERT INTO document_chunk (id, knowledge_base_id, document_id, chunk_index, content, character_count, embedding, embedding_model, embedding_generation, embedding_fence_token, embedded_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                    (long)(i + 1), KB_A, DOC_A, i, contentsA[i], contentsA[i].length(),
                    pgVec, "text-embedding-3-small", 1, (long)(i + 1));
        }

        // ---- Chunks for Document B ----
        String[] contentsB = {
                "Python编程最佳实践包括代码规范、类型注解和单元测试。",
                "Java编程最佳实践包括设计模式、SOLID原则和性能优化。",
                "Go语言编程最佳实践包括并发模型、错误处理和接口设计。"
        };
        for (int i = 0; i < 3; i++) {
            float[] vec = new float[1536];
            for (int j = 0; j < 1536; j++) vec[j] = 0.01f * ((j + i + 10) % 100);
            PGvector pgVec = new PGvector(vec);
            jdbcTemplate.update(
                    "INSERT INTO document_chunk (id, knowledge_base_id, document_id, chunk_index, content, character_count, embedding, embedding_model, embedding_generation, embedding_fence_token, embedded_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                    (long)(i + 4), KB_B, DOC_B, i, contentsB[i], contentsB[i].length(),
                    pgVec, "text-embedding-3-small", 1, (long)(i + 4));
        }

        // ---- Retrieval task READY for Document A ----
        jdbcTemplate.update(
                "INSERT INTO document_retrieval_task (document_id, status, generation, attempt_count, max_attempts, fence_token, message_id, embedding_model, embedding_dimension, es_index_name, indexed_chunk_count, ready_at) " +
                "VALUES (?, 'READY', 1, 1, 3, 3, '550e8400-e29b-41d4-a716-446655440001', 'text-embedding-3-small', 1536, ?, 3, NOW())",
                DOC_A, TEST_INDEX);

        // ---- Retrieval task READY for Document B ----
        jdbcTemplate.update(
                "INSERT INTO document_retrieval_task (document_id, status, generation, attempt_count, max_attempts, fence_token, message_id, embedding_model, embedding_dimension, es_index_name, indexed_chunk_count, ready_at) " +
                "VALUES (?, 'READY', 1, 1, 3, 3, '550e8400-e29b-41d4-a716-446655440002', 'text-embedding-3-small', 1536, ?, 3, NOW())",
                DOC_B, TEST_INDEX);

        // ---- Create ES index and index all chunks ----
        boolean esExists = esClient.indices().exists(e -> e.index(TEST_INDEX)).value();
        if (esExists) {
            esClient.indices().delete(d -> d.index(TEST_INDEX));
        }
        indexManager.ensureIndex();

        // Index chunks for Document A
        for (int i = 0; i < 3; i++) {
            final int chunkIdx = i;
            ElasticsearchChunkDocument doc = new ElasticsearchChunkDocument();
            doc.setChunkId((long)(chunkIdx + 1));
            doc.setDocumentId(DOC_A);
            doc.setKnowledgeBaseId(KB_A);
            doc.setWorkspaceId(WS_A);
            doc.setChunkIndex(chunkIdx);
            doc.setContent(contentsA[chunkIdx]);
            doc.setMetadata(java.util.Collections.emptyMap());
            doc.setIndexGeneration(1);
            doc.setFenceToken((long)(chunkIdx + 1));
            doc.setIndexedAt(Instant.now());
            final ElasticsearchChunkDocument finalDoc = doc;
            esClient.index(idx -> idx
                    .index(TEST_INDEX)
                    .id(String.valueOf(chunkIdx + 1))
                    .document(finalDoc)
                    .refresh(Refresh.WaitFor));
        }

        // Index chunks for Document B
        for (int i = 0; i < 3; i++) {
            final int chunkIdx = i;
            ElasticsearchChunkDocument doc = new ElasticsearchChunkDocument();
            doc.setChunkId((long)(chunkIdx + 4));
            doc.setDocumentId(DOC_B);
            doc.setKnowledgeBaseId(KB_B);
            doc.setWorkspaceId(WS_B);
            doc.setChunkIndex(chunkIdx);
            doc.setContent(contentsB[chunkIdx]);
            doc.setMetadata(java.util.Collections.emptyMap());
            doc.setIndexGeneration(1);
            doc.setFenceToken((long)(chunkIdx + 4));
            doc.setIndexedAt(Instant.now());
            final ElasticsearchChunkDocument finalDoc = doc;
            esClient.index(idx -> idx
                    .index(TEST_INDEX)
                    .id(String.valueOf(chunkIdx + 4))
                    .document(finalDoc)
                    .refresh(Refresh.WaitFor));
        }
    }

    // ================================================================
    // 1. Real knowledge_search → success
    // ================================================================

    @Test
    @DisplayName("1. knowledge_search with real retrieval returns results")
    void knowledgeSearchSuccess() {
        AgentToolExecutionResult result = tool.execute(ctxA,
                new KnowledgeSearchArguments("北京差旅住宿标准", List.of(KB_A), null, 5, false));

        assertThat(result.success()).as("search should succeed").isTrue();
        assertThat(result.content()).as("should contain result text").contains("Found");
        assertThat(result.content()).contains("北京");
        assertThat(result.content()).contains("documentId");
        assertThat(result.content()).doesNotContain("No relevant documents found");
        assertThat(result.metadata()).containsEntry("resultCount", result.metadata().get("resultCount"));
    }

    // ================================================================
    // 2. Content comes from real chunk
    // ================================================================

    @Test
    @DisplayName("2. knowledge_search result content comes from real PG chunk")
    void contentFromRealChunk() {
        AgentToolExecutionResult result = tool.execute(ctxA,
                new KnowledgeSearchArguments("差旅住宿标准", List.of(KB_A), null, 5, false));

        assertThat(result.success()).isTrue();
        // The real chunk content should appear (truncated to 200 chars)
        assertThat(result.content()).contains("差旅");
        assertThat(result.content()).contains("documentId: " + DOC_A);
    }

    // ================================================================
    // 3. READY Gate — COMPLETED + NOT_READY not in results
    // ================================================================

    @Test
    @DisplayName("3. COMPLETED document without READY retrieval task is excluded")
    void readyGateExcludesNonReady() {
        // Create Document C: COMPLETED but NO retrieval task
        jdbcTemplate.update(
                "INSERT INTO document (id, knowledge_base_id, original_file_name, file_extension, content_type, file_size, checksum_sha256, bucket_name, object_key, status, chunk_strategy, chunk_size, chunk_overlap, parser_metadata, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                3L, KB_A, "doc-c.pdf", "pdf", "application/pdf", 512L,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "intellidesk-documents", "key-c", "COMPLETED",
                "RECURSIVE", 1000, 150, "{}", USER_A);

        // Chunk for Document C (has embedding, but no retrieval task)
        float[] vec = new float[1536];
        for (int j = 0; j < 1536; j++) vec[j] = 0.01f * (j % 100);
        PGvector pgVec = new PGvector(vec);
        jdbcTemplate.update(
                "INSERT INTO document_chunk (id, knowledge_base_id, document_id, chunk_index, content, character_count, embedding, embedding_model, embedding_generation, embedding_fence_token, embedded_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                10L, KB_A, 3L, 0, "This document should NOT be retrieved", 40, pgVec, "text-embedding-3-small", 1, 10L);

        // Index chunk C into ES
        ElasticsearchChunkDocument doc = new ElasticsearchChunkDocument();
        doc.setChunkId(10L);
        doc.setDocumentId(3L);
        doc.setKnowledgeBaseId(KB_A);
        doc.setWorkspaceId(WS_A);
        doc.setChunkIndex(0);
        doc.setContent("This document should NOT be retrieved");
        doc.setMetadata(java.util.Collections.emptyMap());
        doc.setIndexGeneration(1);
        doc.setFenceToken(10L);
        doc.setIndexedAt(Instant.now());
        try {
            esClient.index(idx -> idx.index(TEST_INDEX).id("10").document(doc).refresh(Refresh.WaitFor));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        AgentToolExecutionResult result = tool.execute(ctxA,
                new KnowledgeSearchArguments("document", List.of(KB_A), null, 10, false));

        assertThat(result.success()).isTrue();
        // Document 3 (without READY task) should not appear
        assertThat(result.content()).doesNotContain("documentId: 3");
    }

    // ================================================================
    // 4. READY Gate — COMPLETED + READY is retrieved
    // ================================================================

    @Test
    @DisplayName("4. COMPLETED document with READY retrieval task IS retrieved")
    void readyGateAllowsReady() {
        AgentToolExecutionResult result = tool.execute(ctxA,
                new KnowledgeSearchArguments("差旅住宿", List.of(KB_A), null, 5, false));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("documentId: " + DOC_A);
    }

    // ================================================================
    // 5. Cross-workspace KB rejected
    // ================================================================

    @Test
    @DisplayName("5. Cross-workspace KB: ctx.workspace=A + KB B is rejected")
    void crossWorkspaceKbRejected() {
        // User A tries to search KB B (which belongs to Workspace B)
        AgentToolExecutionResult result = tool.execute(ctxA,
                new KnowledgeSearchArguments("编程最佳实践", List.of(KB_B), null, 5, false));

        assertThat(result.success()).as("cross-workspace KB search should fail").isFalse();
        assertThat(result.errorCode()).isNotNull();
    }

    // ================================================================
    // 6. Cross-workspace Document rejected
    // ================================================================

    @Test
    @DisplayName("6. Cross-workspace Document: ctx.workspace=A + KB A + Document B is rejected")
    void crossWorkspaceDocumentRejected() {
        // User A knows KB A and tries to access Document B (which belongs to KB B/WS B)
        AgentToolExecutionResult result = tool.execute(ctxA,
                new KnowledgeSearchArguments("编程最佳实践", List.of(KB_A), List.of(DOC_B), 5, false));

        assertThat(result.success()).as("cross-workspace document search should fail").isFalse();
        assertThat(result.errorCode()).isNotNull();
    }

    // ================================================================
    // 7. Scoped KB — only results from specified KB
    // ================================================================

    @Test
    @DisplayName("7. Scoped KB: searching KB A returns only KB A results")
    void scopedKbOnlyReturnsSpecifiedKb() {
        AgentToolExecutionResult result = tool.execute(ctxA,
                new KnowledgeSearchArguments("标准", List.of(KB_A), null, 5, false));

        assertThat(result.success()).isTrue();
        // All results should reference KB A
        if (!result.content().contains("No relevant documents found")) {
            // Results should not contain KB_B
            assertThat(result.content()).doesNotContain("kbId: " + KB_B);
        }
    }

    // ================================================================
    // 8. Retrieval gracefully handles search with any query
    // ================================================================

    @Test
    @DisplayName("8. Retrieval returns success even with non-matching query")
    void retrievalHandlesAnyQueryGracefully() {
        AgentToolExecutionResult result = tool.execute(ctxA,
                new KnowledgeSearchArguments("xyzzy_nonexistent_term_99999", List.of(KB_A), null, 5, false));

        // Tool must not throw, must return success
        assertThat(result.success()).isTrue();
        // ES may return low-score results, but tool should handle both cases
        assertThat(result.content()).isNotNull();
        assertThat(result.content()).isNotEmpty();
        // Result format is always valid
        assertThat(result.errorCode()).isNull();
    }

    // ================================================================
    // 9. Tool result format is compact (content truncated)
    // ================================================================

    @Test
    @DisplayName("9. Tool result format is compact with truncated content")
    void toolResultFormatIsCompact() {
        AgentToolExecutionResult result = tool.execute(ctxA,
                new KnowledgeSearchArguments("差旅住宿", List.of(KB_A), null, 5, false));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("score:");
        assertThat(result.content()).contains("documentId:");
        assertThat(result.content()).doesNotContain("object_key");
        assertThat(result.content()).doesNotContain("bucket");
        assertThat(result.content()).doesNotContain("embedding");
        assertThat(result.content()).doesNotContain("vector");
    }
}