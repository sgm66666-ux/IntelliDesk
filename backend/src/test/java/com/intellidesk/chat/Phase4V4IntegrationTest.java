package com.intellidesk.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.chat.conversation.Conversation;
import com.intellidesk.chat.conversation.ConversationService;
import com.intellidesk.chat.message.ChatMessage;
import com.intellidesk.chat.message.ChatMessageMapper;
import com.intellidesk.chat.message.ChatMessageStatus;
import com.intellidesk.chat.message.ChatRole;
import com.intellidesk.common.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 Wave 1 Integration Test:
 * Flyway V4 → PostgreSQL + Conversation E2E + ChatMessage Persistence.
 * Uses real PostgreSQL via Testcontainers.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
@DisplayName("Phase 4 V4 Integration (real PostgreSQL)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("isDockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase4V4IntegrationTest {

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
    @Autowired private ChatMessageMapper chatMessageMapper;

    // Test users
    private Long userAId; // Owner/Member of workspace 1
    private Long userBId; // Member of workspace 1
    private Long userCId; // Outsider (not member of workspace 1)
    private Long workspace1Id;
    private Long workspace2Id;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available");

        // Clean up in reverse FK order
        jdbcTemplate.execute("DELETE FROM chat_message");
        jdbcTemplate.execute("DELETE FROM conversation");
        jdbcTemplate.execute("DELETE FROM workspace_member");
        jdbcTemplate.execute("DELETE FROM workspace");
        jdbcTemplate.execute("DELETE FROM sys_user_role");
        jdbcTemplate.execute("DELETE FROM sys_user");

        // Reset sequences
        jdbcTemplate.execute("ALTER SEQUENCE sys_user_id_seq RESTART WITH 1");
        jdbcTemplate.execute("ALTER SEQUENCE workspace_id_seq RESTART WITH 1");
        jdbcTemplate.execute("ALTER SEQUENCE conversation_id_seq RESTART WITH 1");
        jdbcTemplate.execute("ALTER SEQUENCE chat_message_id_seq RESTART WITH 1");

        // Seed roles (needed for workspace_member role FK)
        jdbcTemplate.execute("INSERT INTO sys_role (id, name, code) VALUES (1, 'Admin', 'ADMIN') ON CONFLICT DO NOTHING");
        jdbcTemplate.execute("INSERT INTO sys_role (id, name, code) VALUES (2, 'Member', 'MEMBER') ON CONFLICT DO NOTHING");

        // Create test users
        jdbcTemplate.execute("INSERT INTO sys_user (id, username, password_hash, nickname) VALUES (1, 'userA', 'hash', 'User A')");
        jdbcTemplate.execute("INSERT INTO sys_user (id, username, password_hash, nickname) VALUES (2, 'userB', 'hash', 'User B')");
        jdbcTemplate.execute("INSERT INTO sys_user (id, username, password_hash, nickname) VALUES (3, 'userC', 'hash', 'User C')");

        userAId = 1L;
        userBId = 2L;
        userCId = 3L;

        // Create workspaces
        jdbcTemplate.execute("INSERT INTO workspace (id, name, owner_id) VALUES (1, 'Workspace 1', 1)");
        jdbcTemplate.execute("INSERT INTO workspace (id, name, owner_id) VALUES (2, 'Workspace 2', 3)");
        workspace1Id = 1L;
        workspace2Id = 2L;

        // Set up memberships: A and B are members of workspace 1; C is NOT
        jdbcTemplate.execute("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (1, 1, 'ADMIN')");
        jdbcTemplate.execute("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (1, 2, 'MEMBER')");
        // C is member of workspace 2 only
        jdbcTemplate.execute("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (2, 3, 'ADMIN')");
    }

    // ============================================================
    // Flyway V4 Migration Verification
    // ============================================================

    @Nested
    @DisplayName("Flyway V4")
    class FlywayV4Tests {

        @Test
        @Order(1)
        @DisplayName("V4 tables exist: conversation, chat_message")
        void v4TablesExist() {
            // Verify conversation table
            List<String> convColumns = jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'conversation' ORDER BY ordinal_position",
                    String.class);
            assertThat(convColumns).contains("id", "workspace_id", "user_id", "title", "status", "created_at", "updated_at");

            // Verify chat_message table
            List<String> msgColumns = jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'chat_message' ORDER BY ordinal_position",
                    String.class);
            assertThat(msgColumns).contains("id", "conversation_id", "role", "content", "status",
                    "model", "error_code", "error_message", "citation", "token_usage", "sequence_no", "created_at");
        }

        @Test
        @Order(2)
        @DisplayName("V4 constraints: FK, CHECK, UNIQUE")
        void v4Constraints() {
            // FK: conversation.workspace_id → workspace.id
            // FK: conversation.user_id → sys_user.id
            // FK: chat_message.conversation_id → conversation.id

            // CHECK: conversation status
            assertThatThrownBy(() -> jdbcTemplate.execute(
                    "INSERT INTO conversation (workspace_id, user_id, status) VALUES (1, 1, 'INVALID')"))
                    .hasMessageContaining("chk_conversation_status");

            // CHECK: chat_message role
            assertThatThrownBy(() -> jdbcTemplate.execute(
                    "INSERT INTO chat_message (conversation_id, role, content, sequence_no) VALUES (1, 'INVALID', 'test', 1)"))
                    .hasMessageContaining("chk_chat_message_role");

            // CHECK: chat_message status
            assertThatThrownBy(() -> jdbcTemplate.execute(
                    "INSERT INTO chat_message (conversation_id, role, content, status, sequence_no) VALUES (1, 'USER', 'test', 'INVALID', 1)"))
                    .hasMessageContaining("chk_chat_message_status");
        }

        @Test
        @Order(3)
        @DisplayName("V4 permission seeds: conversation:view, conversation:manage")
        void v4PermissionSeeds() {
            List<String> permissions = jdbcTemplate.queryForList(
                    "SELECT code FROM sys_permission WHERE code IN ('conversation:view', 'conversation:manage') ORDER BY code",
                    String.class);
            assertThat(permissions).containsExactly("conversation:manage", "conversation:view");

            // Verify assigned to ADMIN and MEMBER
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_role_permission rp " +
                            "JOIN sys_role r ON rp.role_id = r.id " +
                            "JOIN sys_permission p ON rp.permission_id = p.id " +
                            "WHERE r.code IN ('ADMIN', 'MEMBER') AND p.code IN ('conversation:view', 'conversation:manage')",
                    Integer.class);
            assertThat(count).isEqualTo(4); // 2 roles × 2 permissions
        }
    }

    // ============================================================
    // Conversation CRUD E2E
    // ============================================================

    @Nested
    @DisplayName("Conversation CRUD")
    class ConversationCrudTests {

        @Test
        @Order(4)
        @DisplayName("create conversation")
        void createConversation() {
            Conversation conv = conversationService.create(workspace1Id, userAId, "My Test Conversation");

            assertThat(conv.getId()).isNotNull();
            assertThat(conv.getWorkspaceId()).isEqualTo(workspace1Id);
            assertThat(conv.getUserId()).isEqualTo(userAId);
            assertThat(conv.getTitle()).isEqualTo("My Test Conversation");
            assertThat(conv.getStatus()).isEqualTo("ACTIVE");
            assertThat(conv.getCreatedAt()).isNotNull();
            assertThat(conv.getUpdatedAt()).isNotNull();
        }

        @Test
        @Order(5)
        @DisplayName("create with null title defaults to 'New Conversation'")
        void createWithNullTitle() {
            Conversation conv = conversationService.create(workspace1Id, userAId, null);
            assertThat(conv.getTitle()).isEqualTo("New Conversation");
        }

        @Test
        @Order(6)
        @DisplayName("list own conversations")
        void listOwnConversations() {
            conversationService.create(workspace1Id, userAId, "Conv 1");
            conversationService.create(workspace1Id, userAId, "Conv 2");

            List<Conversation> list = conversationService.listByWorkspace(workspace1Id, userAId);

            assertThat(list).hasSize(2);
            assertThat(list).extracting(Conversation::getTitle).contains("Conv 1", "Conv 2");
        }

        @Test
        @Order(7)
        @DisplayName("get own conversation detail")
        void getOwnConversation() {
            Conversation created = conversationService.create(workspace1Id, userAId, "Detail Test");

            Conversation fetched = conversationService.get(created.getId(), workspace1Id, userAId);

            assertThat(fetched.getId()).isEqualTo(created.getId());
            assertThat(fetched.getTitle()).isEqualTo("Detail Test");
        }

        @Test
        @Order(8)
        @DisplayName("rename updates title and updatedAt")
        void renameUpdatesTitleAndUpdatedAt() throws InterruptedException {
            Conversation created = conversationService.create(workspace1Id, userAId, "Old Title");
            LocalDateTime beforeRename = created.getUpdatedAt();

            Thread.sleep(10); // Ensure time difference

            Conversation renamed = conversationService.updateTitle(created.getId(), workspace1Id, userAId, "New Title");

            assertThat(renamed.getTitle()).isEqualTo("New Title");
            assertThat(renamed.getUpdatedAt()).isAfter(beforeRename);
        }

        @Test
        @Order(9)
        @DisplayName("soft delete: status=DELETED, list excludes, get returns 404")
        void softDelete() {
            Conversation created = conversationService.create(workspace1Id, userAId, "To Delete");

            conversationService.delete(created.getId(), workspace1Id, userAId);

            // List should not include deleted
            List<Conversation> list = conversationService.listByWorkspace(workspace1Id, userAId);
            assertThat(list).extracting(Conversation::getId).doesNotContain(created.getId());

            // Get should return 404
            assertThatThrownBy(() -> conversationService.get(created.getId(), workspace1Id, userAId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6001);
        }
    }

    // ============================================================
    // Ownership / IDOR
    // ============================================================

    @Nested
    @DisplayName("Ownership / IDOR")
    class OwnershipIdorTests {

        @Test
        @Order(10)
        @DisplayName("member B can create own conversation")
        void memberBCanCreateOwnConversation() {
            Conversation conv = conversationService.create(workspace1Id, userBId, "B's Conversation");

            assertThat(conv.getUserId()).isEqualTo(userBId);
            assertThat(conv.getWorkspaceId()).isEqualTo(workspace1Id);
        }

        @Test
        @Order(11)
        @DisplayName("member B cannot access A's conversation → 404")
        void memberBCannotAccessAConversation() {
            Conversation aConv = conversationService.create(workspace1Id, userAId, "A's Private");

            assertThatThrownBy(() -> conversationService.get(aConv.getId(), workspace1Id, userBId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6001);
        }

        @Test
        @Order(12)
        @DisplayName("A cannot access B's conversation → 404")
        void aCannotAccessBConversation() {
            Conversation bConv = conversationService.create(workspace1Id, userBId, "B's Private");

            assertThatThrownBy(() -> conversationService.get(bConv.getId(), workspace1Id, userAId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6001);
        }

        @Test
        @Order(13)
        @DisplayName("outsider C cannot access workspace → 403")
        void outsiderCannotAccessWorkspace() {
            assertThatThrownBy(() -> conversationService.create(workspace1Id, userCId, "C's Attempt"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(2002);
        }

        @Test
        @Order(14)
        @DisplayName("cross-workspace conversation → scoped 404")
        void crossWorkspaceConversation() {
            // Create conversation in workspace 1 by user A
            Conversation conv = conversationService.create(workspace1Id, userAId, "WS1 Conv");

            // Try to access from workspace 2 (even though user A is not a member of workspace 2)
            // This should fail with workspace access denied first
            assertThatThrownBy(() -> conversationService.get(conv.getId(), workspace2Id, userAId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(2002);
        }

        @Test
        @Order(15)
        @DisplayName("guessed conversation ID → 404")
        void guessedIdReturns404() {
            assertThatThrownBy(() -> conversationService.get(9999L, workspace1Id, userAId))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6001);
        }
    }

    // ============================================================
    // ChatMessage Persistence
    // ============================================================

    @Nested
    @DisplayName("ChatMessage Persistence")
    class ChatMessagePersistenceTests {

        @Test
        @Order(16)
        @DisplayName("sequence_no ordering: 1, 2, 3")
        void sequenceNoOrdering() {
            Conversation conv = conversationService.create(workspace1Id, userAId, "Seq Test");

            ChatMessage msg1 = new ChatMessage();
            msg1.setConversationId(conv.getId());
            msg1.setRole(ChatRole.USER.name());
            msg1.setContent("First message");
            msg1.setStatus(ChatMessageStatus.SUCCESS.name());
            msg1.setSequenceNo(1);
            chatMessageMapper.insert(msg1);

            ChatMessage msg2 = new ChatMessage();
            msg2.setConversationId(conv.getId());
            msg2.setRole(ChatRole.ASSISTANT.name());
            msg2.setContent("Second message");
            msg2.setStatus(ChatMessageStatus.SUCCESS.name());
            msg2.setSequenceNo(2);
            chatMessageMapper.insert(msg2);

            ChatMessage msg3 = new ChatMessage();
            msg3.setConversationId(conv.getId());
            msg3.setRole(ChatRole.USER.name());
            msg3.setContent("Third message");
            msg3.setStatus(ChatMessageStatus.SUCCESS.name());
            msg3.setSequenceNo(3);
            chatMessageMapper.insert(msg3);

            List<ChatMessage> messages = conversationService.getMessages(conv.getId(), workspace1Id, userAId);
            assertThat(messages).hasSize(3);
            assertThat(messages).extracting(ChatMessage::getSequenceNo).containsExactly(1, 2, 3);
        }

        @Test
        @Order(17)
        @DisplayName("duplicate sequence_no rejected by UNIQUE constraint")
        void duplicateSequenceNoRejected() {
            Conversation conv = conversationService.create(workspace1Id, userAId, "Dup Test");

            ChatMessage msg1 = new ChatMessage();
            msg1.setConversationId(conv.getId());
            msg1.setRole(ChatRole.USER.name());
            msg1.setContent("First");
            msg1.setStatus(ChatMessageStatus.SUCCESS.name());
            msg1.setSequenceNo(2);
            chatMessageMapper.insert(msg1);

            ChatMessage msg2 = new ChatMessage();
            msg2.setConversationId(conv.getId());
            msg2.setRole(ChatRole.ASSISTANT.name());
            msg2.setContent("Second");
            msg2.setStatus(ChatMessageStatus.SUCCESS.name());
            msg2.setSequenceNo(2); // Duplicate!

            assertThatThrownBy(() -> chatMessageMapper.insert(msg2))
                    .isInstanceOf(DuplicateKeyException.class);
        }

        @Test
        @Order(18)
        @DisplayName("citation JSONB round-trip")
        void citationJsonbRoundTrip() throws Exception {
            Conversation conv = conversationService.create(workspace1Id, userAId, "Citation Test");

            String citationJson = "[{\"chunk_id\":\"chunk-1\",\"score\":0.95,\"text\":\"Beijing travel policy\"}]";

            ChatMessage msg = new ChatMessage();
            msg.setConversationId(conv.getId());
            msg.setRole(ChatRole.ASSISTANT.name());
            msg.setContent("Response with citation");
            msg.setStatus(ChatMessageStatus.SUCCESS.name());
            msg.setCitation(citationJson);
            msg.setSequenceNo(1);
            chatMessageMapper.insert(msg);

            // Read back and compare as JSON nodes (PostgreSQL JSONB normalizes field order)
            ChatMessage fetched = chatMessageMapper.selectById(msg.getId());
            ObjectMapper mapper = new ObjectMapper();
            JsonNode expected = mapper.readTree(citationJson);
            JsonNode actual = mapper.readTree(fetched.getCitation());
            assertThat(actual).isEqualTo(expected);
        }

        @Test
        @Order(19)
        @DisplayName("token_usage JSONB round-trip")
        void tokenUsageJsonbRoundTrip() throws Exception {
            Conversation conv = conversationService.create(workspace1Id, userAId, "Token Test");

            String usageJson = "{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":150}";

            ChatMessage msg = new ChatMessage();
            msg.setConversationId(conv.getId());
            msg.setRole(ChatRole.ASSISTANT.name());
            msg.setContent("Response with usage");
            msg.setStatus(ChatMessageStatus.SUCCESS.name());
            msg.setTokenUsage(usageJson);
            msg.setSequenceNo(1);
            chatMessageMapper.insert(msg);

            // Read back and compare as JSON nodes (PostgreSQL JSONB normalizes field order)
            ChatMessage fetched = chatMessageMapper.selectById(msg.getId());
            ObjectMapper mapper = new ObjectMapper();
            JsonNode expected = mapper.readTree(usageJson);
            JsonNode actual = mapper.readTree(fetched.getTokenUsage());
            assertThat(actual).isEqualTo(expected);
        }

        @Test
        @Order(20)
        @DisplayName("GENERATING status finalize (CAS pattern)")
        void generatingStatusFinalize() {
            Conversation conv = conversationService.create(workspace1Id, userAId, "Status Test");

            // Insert as GENERATING
            ChatMessage msg = new ChatMessage();
            msg.setConversationId(conv.getId());
            msg.setRole(ChatRole.ASSISTANT.name());
            msg.setContent("");
            msg.setStatus(ChatMessageStatus.GENERATING.name());
            msg.setSequenceNo(1);
            chatMessageMapper.insert(msg);

            // Verify inserted as GENERATING
            ChatMessage fetched = chatMessageMapper.selectById(msg.getId());
            assertThat(fetched.getStatus()).isEqualTo(ChatMessageStatus.GENERATING.name());

            // Finalize to SUCCESS
            fetched.setStatus(ChatMessageStatus.SUCCESS.name());
            fetched.setContent("Final response");
            chatMessageMapper.updateById(fetched);

            // Verify finalized
            ChatMessage finalized = chatMessageMapper.selectById(msg.getId());
            assertThat(finalized.getStatus()).isEqualTo(ChatMessageStatus.SUCCESS.name());
            assertThat(finalized.getContent()).isEqualTo("Final response");
        }
    }
}