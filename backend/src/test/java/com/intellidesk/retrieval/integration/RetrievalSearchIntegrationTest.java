package com.intellidesk.retrieval.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.intellidesk.TestInfrastructureConfig;
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
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestInfrastructureConfig.class, RetrievalSearchIntegrationTest.TestRetrievalConfig.class})
@DisplayName("Retrieval Search Integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
@EnabledIf("isDockerAvailable")
class RetrievalSearchIntegrationTest {

    private static final String TEST_INDEX = "test-retrieval-search-v1";
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

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ElasticsearchClient esClient;
    @Autowired private ElasticsearchIndexManager indexManager;
    @Autowired private VectorRetriever vectorRetriever;
    @Autowired private KeywordRetriever keywordRetriever;
    @Autowired private HybridRetriever hybridRetriever;
    @Autowired private RetrievalHydrator hydrator;

    private Long workspaceId = 1L;
    private Long kbId = 1L;
    private Long docId = 1L;

    @BeforeEach
    void setUp() throws Exception {
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

        // Insert test user
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password_hash, email, status) VALUES (?, ?, ?, ?, ?)",
                1L, "testuser", "hash", "test@test.com", 1);

        // Insert workspace
        jdbcTemplate.update("INSERT INTO workspace (id, name, owner_id) VALUES (?, ?, ?)",
                workspaceId, "Test WS", 1L);

        // Insert knowledge base
        jdbcTemplate.update("INSERT INTO knowledge_base (id, workspace_id, name, status, created_by) VALUES (?, ?, ?, ?, ?)",
                kbId, workspaceId, "Test KB", "ACTIVE", 1L);

        // Insert COMPLETED document
        jdbcTemplate.update(
                "INSERT INTO document (id, knowledge_base_id, original_file_name, file_extension, content_type, file_size, checksum_sha256, bucket_name, object_key, status, chunk_strategy, chunk_size, chunk_overlap, parser_metadata, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                docId, kbId, "test.pdf", "pdf", "application/pdf", 1024L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "intellidesk-documents", "test-key", "COMPLETED",
                "RECURSIVE", 1000, 150, "{}", 1L);

        // Insert chunks with embeddings
        for (int i = 0; i < 3; i++) {
            float[] vec = new float[1536];
            for (int j = 0; j < 1536; j++) vec[j] = 0.01f * ((j + i) % 100);
            PGvector pgVec = new PGvector(vec);
            jdbcTemplate.update(
                    "INSERT INTO document_chunk (id, knowledge_base_id, document_id, chunk_index, content, character_count, embedding, embedding_model, embedding_generation, embedding_fence_token, embedded_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                    (long)(i + 1), kbId, docId, i, "Test content for chunk " + i, 50, pgVec, "text-embedding-3-small", 1, (long)(i + 1));
        }

        // Create retrieval task READY
        jdbcTemplate.update(
                "INSERT INTO document_retrieval_task (document_id, status, generation, attempt_count, max_attempts, fence_token, message_id, embedding_model, embedding_dimension, es_index_name, indexed_chunk_count, ready_at) " +
                "VALUES (?, 'READY', 1, 1, 3, 3, '550e8400-e29b-41d4-a716-446655440000', 'text-embedding-3-small', 1536, ?, 3, NOW())",
                docId, TEST_INDEX);

        // Create ES index and index chunks
        boolean esExists = esClient.indices().exists(e -> e.index(TEST_INDEX)).value();
        if (esExists) {
            esClient.indices().delete(d -> d.index(TEST_INDEX));
        }
        indexManager.ensureIndex();

        // Index chunks into ES
        for (int i = 0; i < 3; i++) {
            final int chunkIdx = i;
            ElasticsearchChunkDocument doc = new ElasticsearchChunkDocument();
            doc.setChunkId((long)(chunkIdx + 1));
            doc.setDocumentId(docId);
            doc.setKnowledgeBaseId(kbId);
            doc.setWorkspaceId(workspaceId);
            doc.setChunkIndex(chunkIdx);
            doc.setContent("Test content for chunk " + chunkIdx);
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
    }

    // ================================================================
    // 1. VECTOR query
    // ================================================================

    @Test
    @DisplayName("1. VECTOR query returns cosine similarity results")
    void shouldReturnVectorResults() {
        List<RetrievalResult> results = vectorRetriever.retrieve("test query", workspaceId, List.of(kbId), null, 50, 10);

        assertThat(results).isNotEmpty();
        for (RetrievalResult r : results) {
            assertThat(r.getScoreType()).isEqualTo(ScoreType.COSINE_SIMILARITY);
            assertThat(r.getContent()).isNotNull();
            assertThat(r.getDocumentId()).isNotNull();
            assertThat(r.getKnowledgeBaseId()).isNotNull();
        }
    }

    // ================================================================
    // 2. KEYWORD query
    // ================================================================

    @Test
    @DisplayName("2. KEYWORD query returns BM25 results")
    void shouldReturnKeywordResults() {
        List<KeywordResult> results = keywordRetriever.retrieve(
                "test content", workspaceId, List.of(kbId), null, 10);

        assertThat(results).isNotEmpty();
        for (KeywordResult r : results) {
            assertThat(r.getBm25Score()).isNotNull();
            assertThat(r.getBm25Score()).isGreaterThan(0.0);
        }
    }

    // ================================================================
    // 3. HYBRID query
    // ================================================================

    @Test
    @DisplayName("3. HYBRID query returns RRF results after hydration")
    void shouldReturnHybridResults() {
        RetrievalScope scope = RetrievalScope.of(workspaceId, List.of(kbId));
        RetrievalQuery query = new RetrievalQuery("test content", scope);

        // RRF fusion
        List<RetrievalResult> fused = hybridRetriever.retrieve(query, scope, 10, 10);
        assertThat(fused).isNotEmpty();

        // Hydrate from PG (fills in documentId, knowledgeBaseId, content)
        List<RetrievalResult> hydrated = hydrator.hydrate(fused, scope);
        assertThat(hydrated).isNotEmpty();

        for (RetrievalResult r : hydrated) {
            assertThat(r.getScoreType()).isEqualTo(ScoreType.RRF);
            assertThat(r.getRetrievalSource()).isEqualTo(RetrievalSource.HYBRID);
            assertThat(r.getMatchedSources()).isNotEmpty();
            assertThat(r.getDocumentId()).isNotNull();
            assertThat(r.getContent()).isNotNull();
        }
    }

    // ================================================================
    // 4. PG hydration filters stale ES candidates
    // ================================================================

    @Test
    @DisplayName("4. PG hydration removes stale ES candidates")
    void shouldRemoveStaleEsCandidates() {
        // Create a candidate with a chunkId that doesn't exist in PG
        RetrievalResult staleCandidate = new RetrievalResult(999L, docId, kbId, "stale content",
                0.5f, ScoreType.BM25, 0, null);
        staleCandidate.setRetrievalSource(RetrievalSource.KEYWORD);

        RetrievalScope scope = RetrievalScope.of(workspaceId, List.of(kbId));
        List<RetrievalResult> hydrated = hydrator.hydrate(List.of(staleCandidate), scope);

        assertThat(hydrated).isEmpty();
    }

    // ================================================================
    // 5. Valid PG candidate passes hydration
    // ================================================================

    @Test
    @DisplayName("5. Valid PG candidate passes hydration")
    void shouldPassValidCandidateThroughHydration() {
        RetrievalResult validCandidate = new RetrievalResult(1L, docId, kbId, null,
                0.8f, ScoreType.BM25, 0, null);
        validCandidate.setRetrievalSource(RetrievalSource.KEYWORD);

        RetrievalScope scope = RetrievalScope.of(workspaceId, List.of(kbId));
        List<RetrievalResult> hydrated = hydrator.hydrate(List.of(validCandidate), scope);

        assertThat(hydrated).hasSize(1);
        RetrievalResult result = hydrated.get(0);
        assertThat(result.getChunkId()).isEqualTo(1L);
        assertThat(result.getDocumentId()).isEqualTo(docId);
        assertThat(result.getKnowledgeBaseId()).isEqualTo(kbId);
        assertThat(result.getContent()).isEqualTo("Test content for chunk 0");
        assertThat(result.getScore()).isEqualTo(0.8f);
        assertThat(result.getScoreType()).isEqualTo(ScoreType.BM25);
    }

    // ================================================================
    // 6. optional document filter
    // ================================================================

    @Test
    @DisplayName("6. optional document filter works in hybrid retrieval")
    void shouldFilterByDocumentId() {
        RetrievalScope scope = RetrievalScope.of(workspaceId, List.of(kbId), List.of(docId));
        RetrievalQuery query = new RetrievalQuery("test content", scope);

        List<RetrievalResult> fused = hybridRetriever.retrieve(query, scope, 10, 10);
        assertThat(fused).isNotEmpty();

        List<RetrievalResult> hydrated = hydrator.hydrate(fused, scope);
        assertThat(hydrated).isNotEmpty();

        for (RetrievalResult r : hydrated) {
            assertThat(r.getDocumentId()).isEqualTo(docId);
        }
    }
}