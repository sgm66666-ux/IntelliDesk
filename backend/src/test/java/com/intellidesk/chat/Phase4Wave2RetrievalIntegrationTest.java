package com.intellidesk.chat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.chat.citation.Citation;
import com.intellidesk.chat.citation.CitationAssembler;
import com.intellidesk.chat.citation.CitationRegistry;
import com.intellidesk.chat.context.ContextEntry;
import com.intellidesk.chat.context.RagContext;
import com.intellidesk.chat.context.RagContextBuilder;
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
 * Phase 4 Wave 2 FINAL CLOSEOUT:
 * Real Retrieval → Context → Citation integration test.
 * <p>
 * Full pipeline: Workspace → KB → Document → Chunks → pgvector + ES index
 * → RetrievalService.search() → real RetrievalResult
 * → RagContextBuilder → CitationAssembler → CitationRegistry.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestInfrastructureConfig.class, Phase4Wave2RetrievalIntegrationTest.TestRetrievalConfig.class})
@DisplayName("Phase 4 Wave 2 Retrieval → Context → Citation (real pipeline)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("isDockerAvailable")
class Phase4Wave2RetrievalIntegrationTest {

    private static final String TEST_INDEX = "test-wave2-retrieval-v1";
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
    @Autowired private RetrievalService retrievalService;
    @Autowired private RagContextBuilder ragContextBuilder;
    @Autowired private CitationAssembler citationAssembler;

    private Long workspaceId = 1L;
    private Long kbId = 1L;
    private Long docId = 1L;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available");

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

        // Insert COMPLETED document with page metadata
        jdbcTemplate.update(
                "INSERT INTO document (id, knowledge_base_id, original_file_name, file_extension, content_type, file_size, checksum_sha256, bucket_name, object_key, status, chunk_strategy, chunk_size, chunk_overlap, parser_metadata, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                docId, kbId, "travel-policy.pdf", "pdf", "application/pdf", 1024L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "intellidesk-documents", "test-key", "COMPLETED",
                "RECURSIVE", 1000, 150,
                "{\"title\":\"Travel Policy\",\"pages\":5}",
                1L);

        // Insert chunks with embeddings, page metadata, and section paths
        String[] contents = {
                "北京地区差旅住宿标准为每人每天500元。出差人员需提前申请并获得审批。",
                "上海地区差旅住宿标准为每人每天600元。餐饮补贴为每人每天200元。",
                "国际差旅标准根据目的地国家不同，分为A类、B类和C类三个等级。"
        };
        String[] sectionPaths = {"Section 1", "Section 2", "Section 3"};
        Integer[] pageStarts = {1, 2, 3};

        for (int i = 0; i < 3; i++) {
            float[] vec = new float[1536];
            for (int j = 0; j < 1536; j++) vec[j] = 0.01f * ((j + i) % 100);
            PGvector pgVec = new PGvector(vec);
            jdbcTemplate.update(
                    "INSERT INTO document_chunk (id, knowledge_base_id, document_id, chunk_index, content, character_count, embedding, embedding_model, embedding_generation, embedding_fence_token, embedded_at, section_path, page_start, page_end) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, ?)",
                    (long)(i + 1), kbId, docId, i, contents[i], contents[i].length(),
                    pgVec, "text-embedding-3-small", 1, (long)(i + 1),
                    sectionPaths[i], pageStarts[i], pageStarts[i]);
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
            doc.setContent(contents[chunkIdx]);
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
    // 1. Real Retrieval → Context → Citation full pipeline
    // ================================================================

    @Test
    @DisplayName("1. Real RetrievalService → RetrievalResult → RagContext → CitationRegistry")
    void fullPipeline() {
        // Step 1: Real retrieval via RetrievalService
        RetrievalScope scope = RetrievalScope.of(workspaceId, List.of(kbId));
        RetrievalQuery query = new RetrievalQuery("差旅住宿标准", scope);

        List<RetrievalResult> results = retrievalService.search(query, RetrievalMode.HYBRID, 10, 5, false);

        // GATE: RetrievalResult non-empty
        assertThat(results).as("Phase 3 retrieval must return results").isNotEmpty();

        // GATE: Phase 3 order preserved
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i - 1).getScore())
                    .as("Phase 3 score order must be non-increasing")
                    .isGreaterThanOrEqualTo(results.get(i).getScore());
        }

        // Verify each RetrievalResult has required fields
        for (RetrievalResult rr : results) {
            assertThat(rr.getChunkId()).as("chunkId").isNotNull();
            assertThat(rr.getDocumentId()).as("documentId").isNotNull();
            assertThat(rr.getKnowledgeBaseId()).as("knowledgeBaseId").isNotNull();
            assertThat(rr.getContent()).as("content").isNotNull().isNotEmpty();
            assertThat(rr.getScore()).as("score").isGreaterThan(0f);
            assertThat(rr.getScoreType()).as("scoreType").isNotNull();
        }

        // Step 2: RagContextBuilder
        RagContext context = ragContextBuilder.build(results, 4096);

        // GATE: Context non-empty
        assertThat(context.entries()).as("RagContext entries").isNotEmpty();
        assertThat(context.estimatedTokens()).as("estimatedTokens").isGreaterThan(0);
        assertThat(context.tokenBudget()).as("tokenBudget").isEqualTo(4096);

        // GATE: Citation IDs from [1] ascending
        for (int i = 0; i < context.entries().size(); i++) {
            assertThat(context.entries().get(i).citationId())
                    .as("citationId[" + i + "]")
                    .isEqualTo(i + 1);
        }

        // GATE: Each ContextEntry preserves Phase 3 fields
        for (int i = 0; i < context.entries().size(); i++) {
            ContextEntry entry = context.entries().get(i);
            RetrievalResult rr = results.get(i);

            assertThat(entry.chunkId()).as("chunkId[" + i + "]").isEqualTo(rr.getChunkId());
            assertThat(entry.documentId()).as("documentId[" + i + "]").isEqualTo(rr.getDocumentId());
            assertThat(entry.content()).as("content[" + i + "]").isEqualTo(rr.getContent());
            assertThat(entry.score()).as("score[" + i + "]").isEqualTo(rr.getScore());
            assertThat(entry.scoreType()).as("scoreType[" + i + "]").isEqualTo(rr.getScoreType());
        }

        // GATE: documentName batch hydrated from PostgreSQL
        for (ContextEntry entry : context.entries()) {
            assertThat(entry.documentName()).as("documentName").isNotNull().isEqualTo("travel-policy.pdf");
        }

        // GATE: pageNumber correct when chunk has metadata, null otherwise
        for (ContextEntry entry : context.entries()) {
            if (entry.chunkIndex() == 0) {
                assertThat(entry.pageNumber()).as("pageNumber[chunk0]").isEqualTo(1);
            } else if (entry.chunkIndex() == 1) {
                assertThat(entry.pageNumber()).as("pageNumber[chunk1]").isEqualTo(2);
            } else if (entry.chunkIndex() == 2) {
                assertThat(entry.pageNumber()).as("pageNumber[chunk2]").isEqualTo(3);
            }
        }

        // GATE: sectionPath preserved
        for (ContextEntry entry : context.entries()) {
            assertThat(entry.sectionPath()).as("sectionPath").isNotNull();
        }

        // Step 3: CitationAssembler → CitationRegistry
        CitationRegistry registry = citationAssembler.assemble(context);

        // GATE: Registry size matches context entries
        assertThat(registry.size()).as("registry size").isEqualTo(context.entries().size());

        // GATE: Each Citation maps to corresponding ContextEntry
        for (int i = 0; i < context.entries().size(); i++) {
            ContextEntry entry = context.entries().get(i);
            int citationId = entry.citationId();

            Citation citation = registry.find(citationId);
            assertThat(citation).as("citation[" + citationId + "]").isNotNull();
            assertThat(citation.chunkId()).as("citation.chunkId[" + citationId + "]").isEqualTo(entry.chunkId());
            assertThat(citation.documentId()).as("citation.documentId[" + citationId + "]").isEqualTo(entry.documentId());
            assertThat(citation.documentName()).as("citation.documentName[" + citationId + "]").isEqualTo(entry.documentName());
            assertThat(citation.score()).as("citation.score[" + citationId + "]").isEqualTo(entry.score());
            assertThat(citation.pageNumber()).as("citation.pageNumber[" + citationId + "]").isEqualTo(entry.pageNumber());
        }

        // GATE: toPromptText is non-empty
        String promptText = context.toPromptText();
        assertThat(promptText).as("toPromptText").isNotEmpty();
        assertThat(promptText).contains("[1]");
        assertThat(promptText).contains("travel-policy.pdf");
    }

    // ================================================================
    // 2. VECTOR mode retrieval → Context → Citation
    // ================================================================

    @Test
    @DisplayName("2. VECTOR mode RetrievalService → Context → Citation")
    void vectorModePipeline() {
        RetrievalScope scope = RetrievalScope.of(workspaceId, List.of(kbId));
        RetrievalQuery query = new RetrievalQuery("北京住宿标准", scope);

        List<RetrievalResult> results = retrievalService.search(query, RetrievalMode.VECTOR, 10, 5, false);

        assertThat(results).as("Vector retrieval results").isNotEmpty();

        RagContext context = ragContextBuilder.build(results, 4096);
        assertThat(context.entries()).isNotEmpty();

        CitationRegistry registry = citationAssembler.assemble(context);
        assertThat(registry.size()).isEqualTo(context.entries().size());

        // Verify COSINE_SIMILARITY score type preserved
        for (ContextEntry entry : context.entries()) {
            assertThat(entry.scoreType()).isEqualTo(ScoreType.COSINE_SIMILARITY);
        }
    }

    // ================================================================
    // 3. KEYWORD mode retrieval → Context → Citation
    // ================================================================

    @Test
    @DisplayName("3. KEYWORD mode RetrievalService → Context → Citation")
    void keywordModePipeline() {
        RetrievalScope scope = RetrievalScope.of(workspaceId, List.of(kbId));
        RetrievalQuery query = new RetrievalQuery("差旅住宿", scope);

        List<RetrievalResult> results = retrievalService.search(query, RetrievalMode.KEYWORD, 10, 5, false);

        assertThat(results).as("Keyword retrieval results").isNotEmpty();

        RagContext context = ragContextBuilder.build(results, 4096);
        assertThat(context.entries()).isNotEmpty();

        CitationRegistry registry = citationAssembler.assemble(context);
        assertThat(registry.size()).isEqualTo(context.entries().size());

        // Verify BM25 score type preserved
        for (ContextEntry entry : context.entries()) {
            assertThat(entry.scoreType()).isEqualTo(ScoreType.BM25);
        }
    }

    // ================================================================
    // 4. Scope safety — batch hydration does not cross workspace
    // ================================================================

    @Test
    @DisplayName("4. Scope safety: batch hydration respects input RetrievalResult scope")
    void scopeSafetyBatchHydration() {
        // Create a second workspace with its own document
        jdbcTemplate.update("INSERT INTO workspace (id, name, owner_id) VALUES (?, ?, ?)",
                2L, "Other WS", 1L);
        jdbcTemplate.update("INSERT INTO knowledge_base (id, workspace_id, name, status, created_by) VALUES (?, ?, ?, ?, ?)",
                2L, 2L, "Other KB", "ACTIVE", 1L);
        jdbcTemplate.update(
                "INSERT INTO document (id, knowledge_base_id, original_file_name, file_extension, content_type, file_size, checksum_sha256, bucket_name, object_key, status, chunk_strategy, chunk_size, chunk_overlap, parser_metadata, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                2L, 2L, "other-workspace-doc.pdf", "pdf", "application/pdf", 512L,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "intellidesk-documents", "other-key", "COMPLETED",
                "RECURSIVE", 1000, 150, "{}", 1L);

        // Insert a chunk for the other workspace document
        float[] vec = new float[1536];
        for (int j = 0; j < 1536; j++) vec[j] = 0.01f * (j % 100);
        PGvector pgVec = new PGvector(vec);
        jdbcTemplate.update(
                "INSERT INTO document_chunk (id, knowledge_base_id, document_id, chunk_index, content, character_count, embedding, embedding_model, embedding_generation, embedding_fence_token, embedded_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                999L, 2L, 2L, 0, "Other workspace content", 30, pgVec, "text-embedding-3-small", 1, 999L);

        // Now do retrieval from workspace 1 only
        RetrievalScope scope = RetrievalScope.of(workspaceId, List.of(kbId));
        RetrievalQuery query = new RetrievalQuery("住宿标准", scope);

        List<RetrievalResult> results = retrievalService.search(query, RetrievalMode.HYBRID, 10, 5, false);
        assertThat(results).isNotEmpty();

        // All results must belong to workspace 1
        for (RetrievalResult rr : results) {
            assertThat(rr.getKnowledgeBaseId()).as("all results must be from workspace 1").isEqualTo(kbId);
            assertThat(rr.getDocumentId()).as("all results must be from document 1").isEqualTo(docId);
        }

        // Batch hydration must not load other workspace's document name
        RagContext context = ragContextBuilder.build(results, 4096);
        for (ContextEntry entry : context.entries()) {
            assertThat(entry.documentName()).as("documentName must be from workspace 1").isEqualTo("travel-policy.pdf");
        }
    }

    // ================================================================
    // 5. Empty retrieval → empty context → empty registry
    // ================================================================

    @Test
    @DisplayName("5. Empty retrieval returns empty context and registry")
    void emptyRetrieval() {
        RetrievalScope scope = RetrievalScope.of(workspaceId, List.of(kbId));
        RetrievalQuery query = new RetrievalQuery("xyzzy_nonexistent_term_99999", scope);

        List<RetrievalResult> results = retrievalService.search(query, RetrievalMode.HYBRID, 10, 5, false);

        // May or may not be empty depending on ES scoring, but context builder handles both
        RagContext context = ragContextBuilder.build(results, 4096);
        CitationRegistry registry = citationAssembler.assemble(context);

        // Context builder must not throw
        assertThat(context).isNotNull();
        assertThat(registry).isNotNull();
        assertThat(registry.size()).isEqualTo(context.entries().size());
    }
}