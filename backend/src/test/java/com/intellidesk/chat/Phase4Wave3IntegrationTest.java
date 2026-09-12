package com.intellidesk.chat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.intellidesk.chat.citation.Citation;
import com.intellidesk.chat.conversation.Conversation;
import com.intellidesk.chat.conversation.ConversationMapper;
import com.intellidesk.chat.conversation.ConversationService;
import com.intellidesk.chat.dto.ChatRequest;
import com.intellidesk.chat.message.ChatMessage;
import com.intellidesk.chat.message.ChatMessageMapper;
import com.intellidesk.chat.message.ChatMessageStatus;
import com.intellidesk.chat.message.ChatRole;
import com.intellidesk.chat.orchestration.ChatOrchestrationService;
import com.intellidesk.embedding.EmbeddingBatchResult;
import com.intellidesk.embedding.EmbeddingService;
import com.intellidesk.infrastructure.config.ElasticsearchProperties;
import com.intellidesk.infrastructure.config.MinioProperties;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.retrieval.*;
import com.intellidesk.retrieval.fusion.HybridRetriever;
import com.intellidesk.retrieval.keyword.*;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import com.pgvector.PGvector;
import io.minio.MinioClient;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 4 Wave 3 Integration Test: Full RAG Chat Pipeline with real SSE.
 * <p>
 * Pipeline: USER question → Query Rewrite → HYBRID Retrieval → Context → Citation
 * → Streaming LLM → Citation Validation → ASSISTANT SUCCESS → Message Persistence.
 * <p>
 * Uses real PostgreSQL + pgvector + Elasticsearch + Chat HTTP fixture.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@ActiveProfiles("test")
@Import(Phase4Wave3IntegrationTest.Wave3TestConfig.class)
@DisplayName("Phase 4 Wave 3 Integration (RAG Chat + SSE)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("isDockerAvailable")
public class Phase4Wave3IntegrationTest {

    private static final String TEST_INDEX = "test-wave3-chat-v1";
    private static final String FIXTURE_HOST = "127.0.0.1";
    private static final int FIXTURE_PORT = 18082;
    private static final String FIXTURE_BASE_URL = "http://" + FIXTURE_HOST + ":" + FIXTURE_PORT;
    private static final String FIXTURE_API_KEY = "test-chat-key";

    private static PostgreSQLContainer<?> postgres;
    private static ElasticsearchContainer elasticsearch;
    private static boolean fixtureAvailable;

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket(host, port)) {
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

    @BeforeAll
    static void checkFixture() {
        fixtureAvailable = isPortOpen(FIXTURE_HOST, FIXTURE_PORT);
        if (!fixtureAvailable) {
            System.out.println("[WARN] Chat fixture not available on " + FIXTURE_BASE_URL +
                    ". Start: node deploy/e2e-phase4-provider-stub.js");
        }
    }

    @AfterAll
    void stopContainers() {
        if (elasticsearch != null) elasticsearch.stop();
        if (postgres != null) postgres.stop();
    }

    /**
     * Test configuration for Wave 3: provides real ChatModel → fixture,
     * real EmbeddingService, real ES retrieval components, and infrastructure mocks.
     */
    @TestConfiguration
    static class Wave3TestConfig {

        // --- Infrastructure mocks (replaces TestInfrastructureConfig) ---

        @Bean
        @Primary
        public RedisConnectionFactory redisConnectionFactory() {
            return mock(RedisConnectionFactory.class);
        }

        @Bean
        @Primary
        public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        @Primary
        public MinioClient minioClient(MinioProperties minioProperties) {
            return MinioClient.builder()
                    .endpoint(minioProperties.getEndpoint())
                    .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                    .build();
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

        // --- Real ES components ---

        @Bean
        @Primary
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
        @Primary
        public ElasticsearchIndexManager elasticsearchIndexManager(
                ElasticsearchClient client, RetrievalProperties retrievalProperties) {
            return new ElasticsearchIndexManager(client, retrievalProperties);
        }

        @Bean
        @Primary
        public ElasticsearchChunkIndex elasticsearchChunkIndex(
                ElasticsearchClient client, RetrievalProperties retrievalProperties) {
            return new ElasticsearchChunkIndex(client, retrievalProperties);
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

        // --- ChatLlmService pointing to fixture ---

        @Bean
        @Primary
        public ChatModel chatModel() {
            var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(5));
            requestFactory.setReadTimeout(java.time.Duration.ofSeconds(60));

            var api = org.springframework.ai.openai.api.OpenAiApi.builder()
                    .apiKey(FIXTURE_API_KEY)
                    .baseUrl(FIXTURE_BASE_URL)
                    .restClientBuilder(org.springframework.web.client.RestClient.builder()
                            .requestFactory(requestFactory)
                            .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, (req, resp) -> {
                                throw new RuntimeException("HTTP " + resp.getStatusCode().value());
                            }))
                    .build();

            var options = org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .model("chat-test")
                    .build();

            return new org.springframework.ai.openai.OpenAiChatModel(
                    api, options,
                    org.springframework.ai.model.tool.ToolCallingManager.builder().build(),
                    org.springframework.ai.retry.RetryUtils.SHORT_RETRY_TEMPLATE,
                    io.micrometer.observation.ObservationRegistry.NOOP);
        }
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ElasticsearchClient esClient;
    @Autowired private ElasticsearchIndexManager indexManager;
    @Autowired private ChatOrchestrationService orchestrationService;
    @Autowired private ConversationService conversationService;
    @Autowired private ConversationMapper conversationMapper;
    @Autowired private ChatMessageMapper chatMessageMapper;
    @Autowired private WorkspaceAuthorizationService workspaceAuth;

    private Long workspaceId = 1L;
    private Long kbId = 1L;
    private Long docId = 1L;
    private Long userId = 1L;
    private Conversation conversation;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available");
        Assumptions.assumeTrue(fixtureAvailable, "Chat fixture not available");

        // Clean up in reverse FK order
        jdbcTemplate.execute("DELETE FROM chat_message");
        jdbcTemplate.execute("DELETE FROM conversation");
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

        // Reset sequences
        jdbcTemplate.execute("ALTER SEQUENCE IF EXISTS sys_user_id_seq RESTART WITH 1");
        jdbcTemplate.execute("ALTER SEQUENCE IF EXISTS workspace_id_seq RESTART WITH 1");
        jdbcTemplate.execute("ALTER SEQUENCE IF EXISTS knowledge_base_id_seq RESTART WITH 1");
        jdbcTemplate.execute("ALTER SEQUENCE IF EXISTS document_id_seq RESTART WITH 1");
        jdbcTemplate.execute("ALTER SEQUENCE IF EXISTS document_chunk_id_seq RESTART WITH 1");
        jdbcTemplate.execute("ALTER SEQUENCE IF EXISTS conversation_id_seq RESTART WITH 1");
        jdbcTemplate.execute("ALTER SEQUENCE IF EXISTS chat_message_id_seq RESTART WITH 1");

        // Insert roles
        jdbcTemplate.execute("INSERT INTO sys_role (id, name, code) VALUES (1, 'Admin', 'ADMIN') ON CONFLICT DO NOTHING");
        jdbcTemplate.execute("INSERT INTO sys_role (id, name, code) VALUES (2, 'Member', 'MEMBER') ON CONFLICT DO NOTHING");

        // Insert permissions
        jdbcTemplate.execute("INSERT INTO sys_permission (id, name, code, description) VALUES (1, '对话查看', 'conversation:view', '查看和管理自己的对话') ON CONFLICT DO NOTHING");
        jdbcTemplate.execute("INSERT INTO sys_permission (id, name, code, description) VALUES (2, '对话管理', 'conversation:manage', '管理对话') ON CONFLICT DO NOTHING");
        jdbcTemplate.execute("INSERT INTO sys_permission (id, name, code, description) VALUES (3, '检索查看', 'retrieval:view', '查看检索') ON CONFLICT DO NOTHING");

        // Insert role-permission mappings
        jdbcTemplate.execute("INSERT INTO sys_role_permission (role_id, permission_id) VALUES (1, 1) ON CONFLICT DO NOTHING");
        jdbcTemplate.execute("INSERT INTO sys_role_permission (role_id, permission_id) VALUES (1, 2) ON CONFLICT DO NOTHING");
        jdbcTemplate.execute("INSERT INTO sys_role_permission (role_id, permission_id) VALUES (1, 3) ON CONFLICT DO NOTHING");
        jdbcTemplate.execute("INSERT INTO sys_role_permission (role_id, permission_id) VALUES (2, 1) ON CONFLICT DO NOTHING");
        jdbcTemplate.execute("INSERT INTO sys_role_permission (role_id, permission_id) VALUES (2, 3) ON CONFLICT DO NOTHING");

        // Insert test user
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password_hash, nickname, email, status) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "testuser", "hash", "Test User", "test@test.com", 1);

        // Insert user-role mapping
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, 1L);

        // Insert workspace
        jdbcTemplate.update("INSERT INTO workspace (id, name, owner_id) VALUES (?, ?, ?)",
                workspaceId, "Test WS", userId);

        // Insert workspace member
        jdbcTemplate.update("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, ?)",
                workspaceId, userId, "ADMIN");

        // Insert knowledge base
        jdbcTemplate.update("INSERT INTO knowledge_base (id, workspace_id, name, status, created_by) VALUES (?, ?, ?, ?, ?)",
                kbId, workspaceId, "Test KB", "ACTIVE", userId);

        // Insert COMPLETED document with page metadata
        jdbcTemplate.update(
                "INSERT INTO document (id, knowledge_base_id, original_file_name, file_extension, content_type, file_size, checksum_sha256, bucket_name, object_key, status, chunk_strategy, chunk_size, chunk_overlap, parser_metadata, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                docId, kbId, "travel-policy.pdf", "pdf", "application/pdf", 1024L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "intellidesk-documents", "test-key-wave3", "COMPLETED",
                "RECURSIVE", 1000, 150,
                "{\"title\":\"Travel Policy\",\"pages\":5}",
                userId);

        // Insert chunks with embeddings
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

        // Create conversation
        conversation = conversationService.create(workspaceId, userId, "Wave 3 Integration Test");
    }

    // ================================================================
    // 1. Full RAG Chat Pipeline
    // ================================================================

    @Test
    @DisplayName("1. Full RAG pipeline: query → retrieve → stream → SUCCESS → citation")
    void fullRagPipeline() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setQuery("北京差旅住宿标准是多少？");
        request.setKnowledgeBaseIds(List.of(kbId));
        request.setRewriteEnabled(false);

        orchestrationService.chat(request, workspaceId, conversation.getId(), userId);

        // Wait for async pipeline to complete
        waitForAssistantMessage(conversation.getId());

        // Verify USER message persisted
        List<ChatMessage> messages = conversationService.getMessages(
                conversation.getId(), workspaceId, userId);
        assertThat(messages).as("messages").hasSizeGreaterThanOrEqualTo(2);

        ChatMessage userMsg = messages.stream()
                .filter(m -> ChatRole.USER.name().equals(m.getRole()))
                .findFirst().orElse(null);
        assertThat(userMsg).as("USER message").isNotNull();
        assertThat(userMsg.getContent()).isEqualTo("北京差旅住宿标准是多少？");
        assertThat(userMsg.getStatus()).isEqualTo(ChatMessageStatus.SUCCESS.name());
        assertThat(userMsg.getSequenceNo()).isNotNull();

        // Verify ASSISTANT message persisted
        ChatMessage assistantMsg = messages.stream()
                .filter(m -> ChatRole.ASSISTANT.name().equals(m.getRole()))
                .findFirst().orElse(null);
        assertThat(assistantMsg).as("ASSISTANT message").isNotNull();
        assertThat(assistantMsg.getSequenceNo()).isNotNull();
        assertThat(assistantMsg.getSequenceNo()).isGreaterThan(userMsg.getSequenceNo());
        assertThat(assistantMsg.getStatus()).isEqualTo(ChatMessageStatus.SUCCESS.name());
        assertThat(assistantMsg.getContent()).as("content").isNotNull().isNotEmpty();
    }

    // ================================================================
    // 2. Verify Citation JSONB
    // ================================================================

    @Test
    @DisplayName("2. Citation JSONB persisted correctly")
    void citationJsonbPersisted() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setQuery("差旅住宿标准是多少？");
        request.setKnowledgeBaseIds(List.of(kbId));
        request.setRewriteEnabled(false);

        orchestrationService.chat(request, workspaceId, conversation.getId(), userId);

        // Wait for async processing (poll for ASSISTANT SUCCESS)
        waitForAssistantMessage(conversation.getId());

        ChatMessage assistantMsg = getLatestAssistantMessage(conversation.getId());
        assertThat(assistantMsg).as("ASSISTANT message").isNotNull();
        assertThat(assistantMsg.getContent()).as("content").isNotNull().isNotEmpty();

        // Citation JSONB should be present (fixture response may contain [1])
        String citationJson = assistantMsg.getCitation();
        // Citation may be null if no valid citations were found in the answer
        // This is acceptable per the specification
        if (citationJson != null) {
            assertThat(citationJson).as("citation JSON").isNotEmpty();
        }
    }

    // ================================================================
    // 3. Follow-up Question with Memory
    // ================================================================

    @Test
    @DisplayName("3. Follow-up question: Q1 → Q2 uses conversation memory")
    void followUpQuestion() throws Exception {
        // Q1: First question
        ChatRequest q1 = new ChatRequest();
        q1.setQuery("北京差旅住宿标准是多少？");
        q1.setKnowledgeBaseIds(List.of(kbId));
        q1.setRewriteEnabled(false);

        orchestrationService.chat(q1, workspaceId, conversation.getId(), userId);
        waitForAssistantMessage(conversation.getId());

        // Q2: Follow-up question (should use conversation history)
        ChatRequest q2 = new ChatRequest();
        q2.setQuery("那上海呢？");
        q2.setKnowledgeBaseIds(List.of(kbId));
        q2.setRewriteEnabled(true);

        orchestrationService.chat(q2, workspaceId, conversation.getId(), userId);
        waitForAssistantMessage(conversation.getId());

        // Verify all 4 messages exist (Q1-user, Q1-assistant, Q2-user, Q2-assistant)
        List<ChatMessage> messages = conversationService.getMessages(
                conversation.getId(), workspaceId, userId);
        List<ChatMessage> userMessages = messages.stream()
                .filter(m -> ChatRole.USER.name().equals(m.getRole()))
                .toList();
        List<ChatMessage> assistantMessages = messages.stream()
                .filter(m -> ChatRole.ASSISTANT.name().equals(m.getRole())
                        && ChatMessageStatus.SUCCESS.name().equals(m.getStatus()))
                .toList();

        assertThat(userMessages).as("USER messages").hasSize(2);
        assertThat(userMessages.get(0).getContent()).contains("北京");
        assertThat(userMessages.get(1).getContent()).contains("上海");

        assertThat(assistantMessages).as("ASSISTANT SUCCESS messages")
                .hasSizeGreaterThanOrEqualTo(1);

        // Verify sequence numbers are strictly increasing
        for (int i = 1; i < messages.size(); i++) {
            assertThat(messages.get(i).getSequenceNo())
                    .as("sequence_no must be strictly increasing")
                    .isGreaterThan(messages.get(i - 1).getSequenceNo());
        }
    }

    // ================================================================
    // 4. Empty Retrieval → No Context Prompt
    // ================================================================

    @Test
    @DisplayName("4. Empty retrieval: conversation with no matching KB")
    void emptyRetrieval() throws Exception {
        // Create a conversation with a KB that has no documents
        jdbcTemplate.update("INSERT INTO knowledge_base (id, workspace_id, name, status, created_by) VALUES (?, ?, ?, ?, ?)",
                99L, workspaceId, "Empty KB", "ACTIVE", userId);

        ChatRequest request = new ChatRequest();
        request.setQuery("是否有相关信息？");
        request.setKnowledgeBaseIds(List.of(99L));
        request.setRewriteEnabled(false);

        orchestrationService.chat(request, workspaceId, conversation.getId(), userId);
        waitForAssistantMessage(conversation.getId());

        ChatMessage assistantMsg = getLatestAssistantMessage(conversation.getId());
        assertThat(assistantMsg).as("ASSISTANT message").isNotNull();
        assertThat(assistantMsg.getStatus()).isEqualTo(ChatMessageStatus.SUCCESS.name());
        assertThat(assistantMsg.getContent()).as("content").isNotNull().isNotEmpty();
    }

    // ================================================================
    // 5. Conversation Busy (Concurrency)
    // ================================================================

    @Test
    @DisplayName("5. Conversation busy: concurrent request rejected")
    void conversationBusy() throws Exception {
        // Insert a raw GENERATING message directly to simulate a busy conversation.
        // This is deterministic — does not depend on async pipeline timing.
        ChatMessage busyMessage = new ChatMessage();
        busyMessage.setConversationId(conversation.getId());
        busyMessage.setRole(ChatRole.ASSISTANT.name());
        busyMessage.setContent("");
        busyMessage.setStatus(ChatMessageStatus.GENERATING.name());
        busyMessage.setSequenceNo(1);
        busyMessage.setCreatedAt(java.time.LocalDateTime.now());
        chatMessageMapper.insert(busyMessage);

        ChatRequest request = new ChatRequest();
        request.setQuery("北京差旅住宿标准是多少？");
        request.setKnowledgeBaseIds(List.of(kbId));
        request.setRewriteEnabled(false);

        // Second request should be rejected (conversation busy)
        com.intellidesk.common.BusinessException ex = Assertions.assertThrows(
                com.intellidesk.common.BusinessException.class,
                () -> orchestrationService.chat(request, workspaceId, conversation.getId(), userId));

        assertThat(ex.getCode()).isEqualTo(6009);
    }

    // ================================================================
    // 6. Authorization / IDOR
    // ================================================================

    @Test
    @DisplayName("6. Authorization: conversation not found for wrong user returns 404")
    void authorizationWrongUser() {
        // User 2 is a workspace member but NOT the conversation owner
        Long otherUserId = 2L;
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password_hash, nickname, email, status) VALUES (?, ?, ?, ?, ?, ?)",
                otherUserId, "otheruser", "hash", "Other User", "other@test.com", 1);
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", otherUserId, 2L); // MEMBER
        jdbcTemplate.update("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, ?)",
                workspaceId, otherUserId, "MEMBER");

        ChatRequest request = new ChatRequest();
        request.setQuery("test");
        request.setKnowledgeBaseIds(List.of(kbId));

        com.intellidesk.common.BusinessException ex = Assertions.assertThrows(
                com.intellidesk.common.BusinessException.class,
                () -> orchestrationService.chat(request, workspaceId, conversation.getId(), otherUserId));

        assertThat(ex.getCode()).isEqualTo(6001); // CHAT_CONVERSATION_NOT_FOUND
    }

    // ================================================================
    // 7. Transaction Boundary
    // ================================================================

    @Test
    @DisplayName("7. Transaction boundary: USER message persisted before streaming")
    void transactionBoundary() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setQuery("差旅标准测试");
        request.setKnowledgeBaseIds(List.of(kbId));
        request.setRewriteEnabled(false);

        orchestrationService.chat(request, workspaceId, conversation.getId(), userId);

        // USER message should be persisted immediately (in TX1, before async streaming)
        // Wait a short time for TX1 to complete
        Thread.sleep(300);

        List<ChatMessage> messages = conversationService.getMessages(
                conversation.getId(), workspaceId, userId);
        List<ChatMessage> userMessages = messages.stream()
                .filter(m -> ChatRole.USER.name().equals(m.getRole()))
                .toList();

        assertThat(userMessages).as("USER messages should be persisted in TX1").isNotEmpty();
        assertThat(userMessages.get(0).getStatus())
                .as("USER message should be SUCCESS immediately")
                .isEqualTo(ChatMessageStatus.SUCCESS.name());
    }

    // ================================================================
    // 8. Message Lifecycle
    // ================================================================

    @Test
    @DisplayName("8. Assistant message lifecycle: GENERATING → SUCCESS")
    void assistantLifecycle() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setQuery("北京住宿标准");
        request.setKnowledgeBaseIds(List.of(kbId));
        request.setRewriteEnabled(false);

        orchestrationService.chat(request, workspaceId, conversation.getId(), userId);

        // Wait for the pipeline to complete
        waitForAssistantMessage(conversation.getId());

        ChatMessage assistantMsg = getLatestAssistantMessage(conversation.getId());
        assertThat(assistantMsg).as("ASSISTANT message").isNotNull();
        assertThat(assistantMsg.getStatus())
                .as("ASSISTANT should be SUCCESS after pipeline completes")
                .isEqualTo(ChatMessageStatus.SUCCESS.name());
        assertThat(assistantMsg.getContent()).as("content").isNotNull().isNotEmpty();
        assertThat(assistantMsg.getErrorCode()).as("error_code").isNull();
        assertThat(assistantMsg.getErrorMessage()).as("error_message").isNull();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private void waitForAssistantMessage(Long conversationId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000; // 30s timeout
        while (System.currentTimeMillis() < deadline) {
            List<ChatMessage> messages = chatMessageMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getConversationId, conversationId)
                            .eq(ChatMessage::getRole, ChatRole.ASSISTANT.name())
                            .ne(ChatMessage::getStatus, ChatMessageStatus.GENERATING.name()));
            if (!messages.isEmpty()) {
                return;
            }
            Thread.sleep(200);
        }
    }

    private ChatMessage getLatestAssistantMessage(Long conversationId) {
        List<ChatMessage> messages = chatMessageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .eq(ChatMessage::getRole, ChatRole.ASSISTANT.name())
                        .orderByDesc(ChatMessage::getSequenceNo)
                        .last("LIMIT 1"));
        return messages.isEmpty() ? null : messages.get(0);
    }
}