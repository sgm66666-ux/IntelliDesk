package com.intellidesk.chat;

import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.chat.citation.Citation;
import com.intellidesk.chat.citation.CitationAssembler;
import com.intellidesk.chat.citation.CitationRegistry;
import com.intellidesk.chat.citation.CitationValidationResult;
import com.intellidesk.chat.citation.CitationValidator;
import com.intellidesk.chat.context.ContextEntry;
import com.intellidesk.chat.context.RagContext;
import com.intellidesk.chat.context.RagContextBuilder;
import com.intellidesk.chat.conversation.Conversation;
import com.intellidesk.chat.conversation.ConversationService;
import com.intellidesk.chat.memory.ConversationMemoryService;
import com.intellidesk.chat.message.ChatMessage;
import com.intellidesk.chat.message.ChatMessageMapper;
import com.intellidesk.chat.message.ChatMessageStatus;
import com.intellidesk.chat.message.ChatRole;
import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.ScoreType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 Wave 2 Integration Test:
 * Real PostgreSQL — Memory, Citation JSONB, Retrieval compatibility.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
@DisplayName("Phase 4 Wave 2 Integration (real PostgreSQL)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("isDockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase4Wave2IntegrationTest {

    private static PostgreSQLContainer<?> postgres;

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
    }

    @AfterAll
    void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ConversationService conversationService;
    @Autowired private ConversationMemoryService memoryService;
    @Autowired private ChatMessageMapper chatMessageMapper;
    @Autowired private CitationAssembler citationAssembler;
    @Autowired private CitationValidator citationValidator;
    @Autowired private RagContextBuilder ragContextBuilder;

    private Long userId;
    private Long workspaceId;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available");

        jdbcTemplate.execute("DELETE FROM chat_message");
        jdbcTemplate.execute("DELETE FROM conversation");
        jdbcTemplate.execute("DELETE FROM workspace_member");
        jdbcTemplate.execute("DELETE FROM workspace");
        jdbcTemplate.execute("DELETE FROM sys_user_role");
        jdbcTemplate.execute("DELETE FROM sys_user");

        jdbcTemplate.execute("ALTER SEQUENCE sys_user_id_seq RESTART WITH 1");
        jdbcTemplate.execute("ALTER SEQUENCE workspace_id_seq RESTART WITH 1");
        jdbcTemplate.execute("ALTER SEQUENCE conversation_id_seq RESTART WITH 1");
        jdbcTemplate.execute("ALTER SEQUENCE chat_message_id_seq RESTART WITH 1");

        jdbcTemplate.execute("INSERT INTO sys_role (id, name, code) VALUES (1, 'Admin', 'ADMIN') ON CONFLICT DO NOTHING");
        jdbcTemplate.execute("INSERT INTO sys_role (id, name, code) VALUES (2, 'Member', 'MEMBER') ON CONFLICT DO NOTHING");

        jdbcTemplate.execute("INSERT INTO sys_user (id, username, password_hash, nickname) VALUES (1, 'testuser', 'hash', 'Test User')");
        userId = 1L;

        jdbcTemplate.execute("INSERT INTO workspace (id, name, owner_id) VALUES (1, 'Test WS', 1)");
        workspaceId = 1L;

        jdbcTemplate.execute("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (1, 1, 'ADMIN')");

        conversation = conversationService.create(workspaceId, userId, "Integration Test Conv");
    }

    // ============================================================
    // Memory Integration
    // ============================================================

    @Nested
    @DisplayName("Memory Integration")
    class MemoryIntegrationTests {

        @Test
        @Order(1)
        @DisplayName("loadRecentMessages returns SUCCESS USER/ASSISTANT in natural order")
        void loadRecentMessages() {
            insertMessage("USER", "SUCCESS", 1, "Q1");
            insertMessage("ASSISTANT", "SUCCESS", 2, "A1");
            insertMessage("USER", "SUCCESS", 3, "Q2");
            insertMessage("ASSISTANT", "SUCCESS", 4, "A2");

            List<ChatMessage> messages = memoryService.loadRecentMessages(workspaceId, conversation.getId(), userId, 10);

            assertThat(messages).hasSize(4);
            assertThat(messages).extracting(ChatMessage::getSequenceNo).containsExactly(1, 2, 3, 4);
        }

        @Test
        @Order(2)
        @DisplayName("loadRecentMessages excludes GENERATING/FAILED/CANCELLED")
        void excludesNonSuccess() {
            insertMessage("USER", "SUCCESS", 1, "Q1");
            insertMessage("ASSISTANT", "GENERATING", 2, "");
            insertMessage("ASSISTANT", "FAILED", 3, "");
            insertMessage("USER", "SUCCESS", 4, "Q2");

            List<ChatMessage> messages = memoryService.loadRecentMessages(workspaceId, conversation.getId(), userId, 10);

            assertThat(messages).hasSize(2);
            assertThat(messages).extracting(ChatMessage::getSequenceNo).containsExactly(1, 4);
        }

        @Test
        @Order(3)
        @DisplayName("loadRecentMessages excludes SYSTEM/TOOL roles")
        void excludesSystemAndTool() {
            insertMessage("SYSTEM", "SUCCESS", 1, "System");
            insertMessage("USER", "SUCCESS", 2, "Q1");
            insertMessage("ASSISTANT", "SUCCESS", 3, "A1");
            insertMessage("TOOL", "SUCCESS", 4, "Tool");

            List<ChatMessage> messages = memoryService.loadRecentMessages(workspaceId, conversation.getId(), userId, 10);

            assertThat(messages).hasSize(2);
            assertThat(messages).extracting(ChatMessage::getRole).containsExactly("USER", "ASSISTANT");
        }

        @Test
        @Order(4)
        @DisplayName("loadRecentMessages respects maxMessages")
        void respectsMaxMessages() {
            for (int i = 1; i <= 10; i++) {
                insertMessage("USER", "SUCCESS", i, "Q" + i);
            }

            List<ChatMessage> messages = memoryService.loadRecentMessages(workspaceId, conversation.getId(), userId, 5);

            assertThat(messages).hasSize(5);
            // Should be the LAST 5 messages (sequence_no 6-10)
            assertThat(messages).extracting(ChatMessage::getSequenceNo).containsExactly(6, 7, 8, 9, 10);
        }

        @Test
        @Order(5)
        @DisplayName("excludeMessageId works")
        void excludeMessageId() {
            insertMessage("USER", "SUCCESS", 1, "Q1");
            ChatMessage current = insertMessage("USER", "SUCCESS", 2, "Q2-current");

            List<ChatMessage> messages = memoryService.loadRecentMessagesExcluding(
                    workspaceId, conversation.getId(), userId, 10, current.getId());

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).getSequenceNo()).isEqualTo(1);
        }
    }

    // ============================================================
    // Citation JSONB Integration
    // ============================================================

    @Nested
    @DisplayName("Citation JSONB Integration")
    class CitationJsonbIntegrationTests {

        @Test
        @Order(6)
        @DisplayName("citation JSONB saves and round-trips from CitationAssembler")
        void citationJsonbRoundTrip() throws Exception {
            // Build a RagContext with citation entries
            ContextEntry entry = new ContextEntry(1, 10L, 1L, "test.pdf", "北京地区差旅标准为500元。",
                    0, "Section 1", 0.92f, ScoreType.RRF, 3);
            RagContext context = new RagContext(List.of(entry), 10, 4096);

            // Build citation registry
            CitationRegistry registry = citationAssembler.assemble(context);
            assertThat(registry.size()).isEqualTo(1);

            // Serialize to JSON
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String citationJson = mapper.writeValueAsString(registry.all());

            // Save to chat_message
            ChatMessage msg = new ChatMessage();
            msg.setConversationId(conversation.getId());
            msg.setRole(ChatRole.ASSISTANT.name());
            msg.setContent("Response with citation");
            msg.setStatus(ChatMessageStatus.SUCCESS.name());
            msg.setCitation(citationJson);
            msg.setSequenceNo(1);
            chatMessageMapper.insert(msg);

            // Read back
            ChatMessage fetched = chatMessageMapper.selectById(msg.getId());
            assertThat(fetched.getCitation()).isNotNull();

            // Parse and verify
            com.fasterxml.jackson.core.type.TypeReference<List<Citation>> typeRef =
                    new com.fasterxml.jackson.core.type.TypeReference<>() {};
            List<Citation> citations = mapper.readValue(fetched.getCitation(), typeRef);

            assertThat(citations).hasSize(1);
            Citation c = citations.get(0);
            assertThat(c.citationId()).isEqualTo(1);
            assertThat(c.documentName()).isEqualTo("test.pdf");
            assertThat(c.chunkId()).isEqualTo(10L);
            assertThat(c.pageNumber()).isEqualTo(3);
        }
    }

    // ============================================================
    // Citation Validation Integration
    // ============================================================

    @Nested
    @DisplayName("Citation Validation Integration")
    class CitationValidationIntegrationTests {

        @Test
        @Order(7)
        @DisplayName("citation validation with real registry")
        void citationValidationWithRealRegistry() {
            CitationRegistry registry = CitationRegistry.from(List.of(
                    new Citation(1, 1L, "a.pdf", 10L, "content A", 0.9f, 3),
                    new Citation(2, 1L, "b.pdf", 20L, "content B", 0.8f, 5)));

            CitationValidationResult result = citationValidator.validate(
                    "根据资料[1]和[2]，北京标准为500元。", registry);

            assertThat(result.validCitationIds()).containsExactly(1, 2);
            assertThat(result.hasHallucinations()).isFalse();
        }

        @Test
        @Order(8)
        @DisplayName("hallucinated citation detected")
        void hallucinatedCitationDetected() {
            CitationRegistry registry = CitationRegistry.from(List.of(
                    new Citation(1, 1L, "a.pdf", 10L, "content A", 0.9f, 3)));

            CitationValidationResult result = citationValidator.validate(
                    "根据资料[1]和[99]。", registry);

            assertThat(result.validCitationIds()).containsExactly(1);
            assertThat(result.hallucinatedCitationIds()).containsExactly(99);
            assertThat(result.hasHallucinations()).isTrue();
        }
    }

    // ============================================================
    // Retrieval Compatibility
    // ============================================================

    @Nested
    @DisplayName("Retrieval Compatibility")
    class RetrievalCompatibilityTests {

        @Test
        @Order(9)
        @DisplayName("Phase 3 RetrievalResult → RagContext preserves fields")
        void retrievalResultToRagContext() {
            // Create a real RetrievalResult (as Phase 3 would produce)
            RetrievalResult rr = new RetrievalResult(10L, 1L, 1L, "Test content from retrieval",
                    0.92f, ScoreType.RRF, 0, "Section 1");

            RagContext context = ragContextBuilder.build(List.of(rr), 4096);

            assertThat(context.entries()).hasSize(1);
            ContextEntry entry = context.entries().get(0);
            assertThat(entry.chunkId()).isEqualTo(10L);
            assertThat(entry.documentId()).isEqualTo(1L);
            assertThat(entry.content()).isEqualTo("Test content from retrieval");
            assertThat(entry.score()).isEqualTo(0.92f);
            assertThat(entry.scoreType()).isEqualTo(ScoreType.RRF);
            assertThat(entry.sectionPath()).isEqualTo("Section 1");
            assertThat(entry.citationId()).isEqualTo(1);
        }

        @Test
        @Order(10)
        @DisplayName("RetrievalResult → CitationRegistry via assembler")
        void retrievalResultToCitationRegistry() {
            RetrievalResult rr = new RetrievalResult(10L, 1L, 1L, "Retrieved content for citation",
                    0.95f, ScoreType.RRF, 0, "");
            RagContext context = ragContextBuilder.build(List.of(rr), 4096);
            CitationRegistry registry = citationAssembler.assemble(context);

            assertThat(registry.size()).isEqualTo(1);
            Citation c = registry.find(1);
            assertThat(c.chunkId()).isEqualTo(10L);
            assertThat(c.documentId()).isEqualTo(1L);
            assertThat(c.content()).isEqualTo("Retrieved content for citation");
            assertThat(c.score()).isEqualTo(0.95f);
        }
    }

    // ---- Helpers ----

    private ChatMessage insertMessage(String role, String status, int seqNo, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setConversationId(conversation.getId());
        msg.setRole(role);
        msg.setStatus(status);
        msg.setSequenceNo(seqNo);
        msg.setContent(content);
        chatMessageMapper.insert(msg);
        return msg;
    }
}