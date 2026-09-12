package com.intellidesk.retrieval.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.AnalyzeResponse;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.infrastructure.config.ElasticsearchProperties;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.retrieval.keyword.ElasticsearchChunkDocument;
import com.intellidesk.retrieval.keyword.ElasticsearchChunkIndex;
import com.intellidesk.retrieval.keyword.ElasticsearchIndexManager;
import com.intellidesk.retrieval.keyword.KeywordResult;
import com.intellidesk.retrieval.keyword.KeywordRetriever;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestInfrastructureConfig.class, ElasticsearchRetrievalIntegrationTest.TestElasticsearchConfig.class})
@DisplayName("Elasticsearch Retrieval Integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
@EnabledIf("isDockerAvailable")
class ElasticsearchRetrievalIntegrationTest {

    private static final String TEST_INDEX = "test-intellidesk-chunks-v1";
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
    void stopContainer() {
        if (elasticsearch != null) {
            elasticsearch.stop();
        }
    }

    @TestConfiguration
    static class TestElasticsearchConfig {

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
                ElasticsearchClient client,
                RetrievalProperties retrievalProperties) {
            return new ElasticsearchIndexManager(client, retrievalProperties);
        }

        @Bean
        public ElasticsearchChunkIndex elasticsearchChunkIndex(
                ElasticsearchClient client,
                RetrievalProperties retrievalProperties) {
            return new ElasticsearchChunkIndex(client, retrievalProperties);
        }

        @Bean
        public KeywordRetriever keywordRetriever(
                ElasticsearchChunkIndex elasticsearchChunkIndex) {
            return new KeywordRetriever(elasticsearchChunkIndex);
        }
    }

    @Autowired
    private ElasticsearchClient client;

    @Autowired
    private ElasticsearchIndexManager indexManager;

    @Autowired
    private ElasticsearchChunkIndex chunkIndex;

    @Autowired
    private KeywordRetriever keywordRetriever;

    @Autowired
    private RetrievalProperties retrievalProperties;

    @BeforeEach
    void setUp() throws Exception {
        boolean exists = client.indices().exists(e -> e.index(TEST_INDEX)).value();
        if (exists) {
            client.indices().delete(d -> d.index(TEST_INDEX));
        }
        indexManager.ensureIndex();
    }

    // --- helper methods ---

    private ElasticsearchChunkDocument createDoc(long chunkId, long docId, long kbId, long wsId,
                                                  int chunkIdx, String content, int generation, long fenceToken) {
        ElasticsearchChunkDocument doc = new ElasticsearchChunkDocument();
        doc.setChunkId(chunkId);
        doc.setDocumentId(docId);
        doc.setKnowledgeBaseId(kbId);
        doc.setWorkspaceId(wsId);
        doc.setChunkIndex(chunkIdx);
        doc.setContent(content);
        doc.setMetadata(java.util.Collections.emptyMap());
        doc.setIndexGeneration(generation);
        doc.setFenceToken(fenceToken);
        doc.setIndexedAt(Instant.now());
        return doc;
    }

    private void indexDoc(ElasticsearchChunkDocument doc) throws Exception {
        client.index(i -> i
                .index(TEST_INDEX)
                .id(String.valueOf(doc.getChunkId()))
                .document(doc)
                .refresh(Refresh.WaitFor));
    }

    private void indexDocWithVersion(ElasticsearchChunkDocument doc, long version) throws Exception {
        client.index(i -> i
                .index(TEST_INDEX)
                .id(String.valueOf(doc.getChunkId()))
                .document(doc)
                .versionType(VersionType.ExternalGte)
                .version(version)
                .refresh(Refresh.WaitFor));
    }

    private void bulkIndex(List<ElasticsearchChunkDocument> docs, long fenceToken) throws Exception {
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
        for (ElasticsearchChunkDocument doc : docs) {
            bulkBuilder.operations(op -> op.index(idx -> idx
                    .index(TEST_INDEX)
                    .id(String.valueOf(doc.getChunkId()))
                    .document(doc)
                    .versionType(VersionType.ExternalGte)
                    .version(fenceToken)));
        }
        BulkResponse response = client.bulk(b -> b
                .index(TEST_INDEX)
                .operations(bulkBuilder.build().operations())
                .refresh(Refresh.WaitFor));
        if (response.errors()) {
            for (BulkResponseItem item : response.items()) {
                if (item.error() != null) {
                    throw new RuntimeException("Bulk index error: id=" + item.id() + " " + item.error().reason());
                }
            }
        }
    }

    private SearchResponse<ElasticsearchChunkDocument> searchByContent(String query, int size) throws Exception {
        return client.search(s -> s
                .index(TEST_INDEX)
                .query(q -> q.match(m -> m.field("content").query(query)))
                .size(size),
                ElasticsearchChunkDocument.class);
    }

    private long countDocs() throws Exception {
        return client.count(c -> c.index(TEST_INDEX)).count();
    }

    // ================================================================
    // 1. shouldCreateIndexAndValidateMapping
    // ================================================================

    @Test
    @DisplayName("1. should create index and validate mapping with SmartCN analyzer")
    void shouldCreateIndexAndValidateMapping() throws Exception {
        // Verify index exists
        boolean exists = client.indices().exists(e -> e.index(TEST_INDEX)).value();
        assertThat(exists).isTrue();

        // Verify mapping
        GetMappingResponse mappingResponse = client.indices().getMapping(g -> g.index(TEST_INDEX));
        var properties = mappingResponse.get(TEST_INDEX).mappings().properties();
        assertThat(properties).isNotNull();
        assertThat(properties).isNotEmpty();

        // content field should be text with smartcn analyzer
        assertThat(properties.get("content")).isNotNull();
        assertThat(properties.get("content").isText()).isTrue();

        // Verify dynamic mapping is strict
        assertThat(mappingResponse.get(TEST_INDEX).mappings().dynamic()).isEqualTo(DynamicMapping.Strict);

        // Verify expected field types
        assertThat(properties.get("chunkId").isLong()).isTrue();
        assertThat(properties.get("documentId").isLong()).isTrue();
        assertThat(properties.get("knowledgeBaseId").isLong()).isTrue();
        assertThat(properties.get("workspaceId").isLong()).isTrue();
        assertThat(properties.get("chunkIndex").isInteger()).isTrue();
        assertThat(properties.get("indexGeneration").isInteger()).isTrue();
        assertThat(properties.get("fenceToken").isLong()).isTrue();
        assertThat(properties.get("indexedAt").isDate()).isTrue();

        // Verify content field is text type and SmartCN analyzer works
        assertThat(properties.get("content")).isNotNull();
        assertThat(properties.get("content").isText()).isTrue();

        // Verify SmartCN analyzer via Analyze API on this index
        AnalyzeResponse analyzeResponse = client.indices().analyze(a -> a
                .index(TEST_INDEX)
                .field("content")
                .text("人工智能"));
        assertThat(analyzeResponse.tokens()).isNotEmpty();
        // SmartCN should segment "人工智能" into "人工" and "智能"
        List<String> tokens = analyzeResponse.tokens().stream()
                .map(t -> t.token())
                .toList();
        assertThat(tokens).anyMatch(t -> t.contains("智能") || t.contains("人工"));
    }

    // ================================================================
    // 2. shouldValidateSmartCnAnalyzerExists
    // ================================================================

    @Test
    @DisplayName("2. should validate SmartCN analyzer exists and segments Chinese text")
    void shouldValidateSmartCnAnalyzerExists() throws Exception {
        AnalyzeResponse response = client.indices().analyze(a -> a
                .index(TEST_INDEX)
                .analyzer("smartcn")
                .text("人工智能技术发展迅速"));

        assertThat(response.tokens()).isNotNull();
        assertThat(response.tokens()).isNotEmpty();

        // SmartCN should segment Chinese text into multiple tokens
        List<String> tokenTexts = response.tokens().stream()
                .map(t -> t.token())
                .toList();
        assertThat(tokenTexts.size()).isGreaterThan(1);

        // Verify common Chinese segmentation patterns
        boolean hasAI = tokenTexts.stream().anyMatch(t -> t.contains("智能") || t.contains("人工"));
        assertThat(hasAI).isTrue();
    }

    // ================================================================
    // 3. shouldSearchChineseBM25
    // ================================================================

    @Test
    @DisplayName("3. should search Chinese text with BM25")
    void shouldSearchChineseBM25() throws Exception {
        var doc1 = createDoc(1L, 1L, 1L, 1L, 0,
                "人工智能是计算机科学的一个重要分支，旨在创建能够执行通常需要人类智能的任务的系统", 1, 1L);
        var doc2 = createDoc(2L, 1L, 1L, 1L, 1,
                "机器学习是人工智能的核心方法，通过数据训练模型来实现预测和决策", 1, 1L);
        var doc3 = createDoc(3L, 1L, 1L, 1L, 2,
                "自然语言处理使得计算机能够理解人类语言", 1, 1L);

        indexDoc(doc1);
        indexDoc(doc2);
        indexDoc(doc3);

        // Search for "人工智能" - should match only doc1 and doc2 (doc3 doesn't contain "人工智能")
        SearchResponse<ElasticsearchChunkDocument> response = searchByContent("人工智能", 10);
        assertThat(response.hits().hits()).hasSize(2);

        // doc1 and doc2 both contain "人工智能"
        List<Long> chunkIds = response.hits().hits().stream()
                .map(h -> h.source().getChunkId())
                .toList();
        assertThat(chunkIds).containsExactlyInAnyOrder(1L, 2L);
    }

    // ================================================================
    // 4. shouldSearchEnglishBM25
    // ================================================================

    @Test
    @DisplayName("4. should search English text with BM25")
    void shouldSearchEnglishBM25() throws Exception {
        var doc1 = createDoc(1L, 1L, 1L, 1L, 0,
                "Artificial intelligence is a branch of computer science", 1, 1L);
        var doc2 = createDoc(2L, 1L, 1L, 1L, 1,
                "Machine learning is a subset of artificial intelligence", 1, 1L);
        var doc3 = createDoc(3L, 1L, 1L, 1L, 2,
                "Python is a popular programming language for data science", 1, 1L);

        indexDoc(doc1);
        indexDoc(doc2);
        indexDoc(doc3);

        // Search for "artificial intelligence" - should match only doc1 and doc2 (doc3 doesn't contain it)
        SearchResponse<ElasticsearchChunkDocument> response = searchByContent("artificial intelligence", 10);
        assertThat(response.hits().hits()).hasSize(2);

        // Verify results have scores
        for (Hit<ElasticsearchChunkDocument> hit : response.hits().hits()) {
            assertThat(hit.score()).isNotNull();
            assertThat(hit.score()).isGreaterThan(0.0);
        }
    }

    // ================================================================
    // 5. shouldSearchMixedChineseEnglish
    // ================================================================

    @Test
    @DisplayName("5. should search mixed Chinese-English content")
    void shouldSearchMixedChineseEnglish() throws Exception {
        var doc1 = createDoc(1L, 1L, 1L, 1L, 0,
                "深度学习的框架如 PyTorch 和 TensorFlow 广泛应用于 AI 研究和生产环境", 1, 1L);
        var doc2 = createDoc(2L, 1L, 1L, 1L, 1,
                "PyTorch is a popular deep learning framework developed by Meta AI", 1, 1L);
        var doc3 = createDoc(3L, 1L, 1L, 1L, 2,
                "传统机器学习算法如决策树和随机森林在结构化数据中表现良好", 1, 1L);

        indexDoc(doc1);
        indexDoc(doc2);
        indexDoc(doc3);

        // Search for "PyTorch 深度学习" - SmartCN segments match all Chinese docs with "学习"
        SearchResponse<ElasticsearchChunkDocument> response = searchByContent("PyTorch 深度学习", 10);
        assertThat(response.hits().hits()).hasSize(3);

        // doc1 and doc2 should rank higher for "PyTorch" match
        List<Long> chunkIds = response.hits().hits().stream()
                .map(h -> h.source().getChunkId())
                .toList();
        assertThat(chunkIds.get(0)).isIn(1L, 2L);
        assertThat(chunkIds.get(1)).isIn(1L, 2L);
    }

    // ================================================================
    // 6. shouldFilterByWorkspaceId
    // ================================================================

    @Test
    @DisplayName("6. should filter by workspaceId")
    void shouldFilterByWorkspaceId() throws Exception {
        var doc1 = createDoc(1L, 1L, 1L, 1L, 0, "Elasticsearch full-text search", 1, 1L);
        var doc2 = createDoc(2L, 2L, 2L, 2L, 0, "Elasticsearch full-text search", 1, 1L);

        indexDoc(doc1);
        indexDoc(doc2);

        // Search with workspaceId filter (wsId=1)
        SearchResponse<ElasticsearchChunkDocument> response = client.search(s -> s
                .index(TEST_INDEX)
                .query(q -> q.bool(b -> b
                        .must(m -> m.match(mq -> mq.field("content").query("Elasticsearch")))
                        .filter(f -> f.term(t -> t.field("workspaceId").value(1L)))))
                .size(10),
                ElasticsearchChunkDocument.class);

        assertThat(response.hits().hits()).hasSize(1);
        assertThat(response.hits().hits().get(0).source().getWorkspaceId()).isEqualTo(1L);
        assertThat(response.hits().hits().get(0).source().getChunkId()).isEqualTo(1L);
    }

    // ================================================================
    // 7. shouldFilterByKnowledgeBaseId
    // ================================================================

    @Test
    @DisplayName("7. should filter by knowledgeBaseId")
    void shouldFilterByKnowledgeBaseId() throws Exception {
        var doc1 = createDoc(1L, 1L, 1L, 1L, 0, "Elasticsearch BM25 scoring", 1, 1L);
        var doc2 = createDoc(2L, 2L, 2L, 1L, 0, "Elasticsearch BM25 scoring", 1, 1L);

        indexDoc(doc1);
        indexDoc(doc2);

        // Search with knowledgeBaseId filter (kbId=1)
        SearchResponse<ElasticsearchChunkDocument> response = client.search(s -> s
                .index(TEST_INDEX)
                .query(q -> q.bool(b -> b
                        .must(m -> m.match(mq -> mq.field("content").query("BM25")))
                        .filter(f -> f.term(t -> t.field("knowledgeBaseId").value(1L)))))
                .size(10),
                ElasticsearchChunkDocument.class);

        assertThat(response.hits().hits()).hasSize(1);
        assertThat(response.hits().hits().get(0).source().getKnowledgeBaseId()).isEqualTo(1L);
        assertThat(response.hits().hits().get(0).source().getChunkId()).isEqualTo(1L);
    }

    // ================================================================
    // 8. shouldFilterByOptionalDocumentId
    // ================================================================

    @Test
    @DisplayName("8. should filter by optional documentId")
    void shouldFilterByOptionalDocumentId() throws Exception {
        var doc1 = createDoc(1L, 1L, 1L, 1L, 0, "full-text search with filters", 1, 1L);
        var doc2 = createDoc(2L, 2L, 1L, 1L, 0, "full-text search with filters", 1, 1L);

        indexDoc(doc1);
        indexDoc(doc2);

        // Search with documentId filter (docId=1)
        SearchResponse<ElasticsearchChunkDocument> response = client.search(s -> s
                .index(TEST_INDEX)
                .query(q -> q.bool(b -> b
                        .must(m -> m.match(mq -> mq.field("content").query("full-text")))
                        .filter(f -> f.term(t -> t.field("documentId").value(1L)))))
                .size(10),
                ElasticsearchChunkDocument.class);

        assertThat(response.hits().hits()).hasSize(1);
        assertThat(response.hits().hits().get(0).source().getDocumentId()).isEqualTo(1L);
        assertThat(response.hits().hits().get(0).source().getChunkId()).isEqualTo(1L);
    }

    // ================================================================
    // 9. shouldBulkUpsertAndIdempotentReupsert
    // ================================================================

    @Test
    @DisplayName("9. should bulk upsert and idempotent re-upsert with same fenceToken")
    void shouldBulkUpsertAndIdempotentReupsert() throws Exception {
        var doc1 = createDoc(1L, 1L, 1L, 1L, 0, "bulk upsert test one", 1, 1L);
        var doc2 = createDoc(2L, 1L, 1L, 1L, 1, "bulk upsert test two", 1, 1L);
        var doc3 = createDoc(3L, 1L, 1L, 1L, 2, "bulk upsert test three", 1, 1L);

        // First bulk index
        bulkIndex(List.of(doc1, doc2, doc3), 1L);
        assertThat(countDocs()).isEqualTo(3);

        // Idempotent re-upsert with same fenceToken (ExternalGte allows version >= existing)
        bulkIndex(List.of(doc1, doc2, doc3), 1L);
        assertThat(countDocs()).isEqualTo(3);

        // Verify content is still correct
        SearchResponse<ElasticsearchChunkDocument> response = searchByContent("bulk upsert test", 10);
        assertThat(response.hits().hits()).hasSize(3);
    }

    // ================================================================
    // 10. shouldRejectLowerFenceVersion
    // ================================================================

    @Test
    @DisplayName("10. should reject lower fence version via ExternalGte")
    void shouldRejectLowerFenceVersion() throws Exception {
        var doc = createDoc(1L, 1L, 1L, 1L, 0, "version conflict test content", 1, 10L);

        // Index with version 10
        indexDocWithVersion(doc, 10L);
        assertThat(countDocs()).isEqualTo(1);

        // Try to index same doc with lower version 5 — should fail
        assertThatThrownBy(() -> indexDocWithVersion(doc, 5L))
                .hasMessageContaining("version");

        // Verify the document still has the original content
        SearchResponse<ElasticsearchChunkDocument> response = searchByContent("version conflict test", 1);
        assertThat(response.hits().hits()).hasSize(1);

        // Index with same version (10) should succeed (Gte = greater than or equal)
        indexDocWithVersion(doc, 10L);
        assertThat(countDocs()).isEqualTo(1);

        // Index with higher version (15) should succeed
        indexDocWithVersion(doc, 15L);
        assertThat(countDocs()).isEqualTo(1);
    }

    // ================================================================
    // 11. shouldDeleteByDocumentId
    // ================================================================

    @Test
    @DisplayName("11. should delete chunks by documentId")
    void shouldDeleteByDocumentId() throws Exception {
        var doc1 = createDoc(1L, 1L, 1L, 1L, 0, "delete test chunk one", 1, 1L);
        var doc2 = createDoc(2L, 1L, 1L, 1L, 1, "delete test chunk two", 1, 1L);
        var doc3 = createDoc(3L, 2L, 1L, 1L, 0, "delete test chunk in other doc", 1, 1L);

        indexDoc(doc1);
        indexDoc(doc2);
        indexDoc(doc3);
        assertThat(countDocs()).isEqualTo(3);

        // Delete by documentId=1
        client.deleteByQuery(d -> d
                .index(TEST_INDEX)
                .refresh(true)
                .query(q -> q.term(t -> t.field("documentId").value(1L))));

        assertThat(countDocs()).isEqualTo(1);

        // Verify remaining chunk
        SearchResponse<ElasticsearchChunkDocument> response = searchByContent("delete test", 10);
        assertThat(response.hits().hits()).hasSize(1);
        assertThat(response.hits().hits().get(0).source().getDocumentId()).isEqualTo(2L);
    }

    // ================================================================
    // 12. shouldReturnEmptyResultForNoMatches
    // ================================================================

    @Test
    @DisplayName("12. should return empty result for no matches")
    void shouldReturnEmptyResultForNoMatches() throws Exception {
        var doc = createDoc(1L, 1L, 1L, 1L, 0, "Java Spring Boot application", 1, 1L);
        indexDoc(doc);

        // Search for something that doesn't exist
        SearchResponse<ElasticsearchChunkDocument> response = searchByContent("xyzzy_nonexistent_term_42", 10);
        assertThat(response.hits().hits()).isEmpty();
        assertThat(response.hits().total().value()).isEqualTo(0);
    }
}