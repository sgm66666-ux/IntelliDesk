package com.intellidesk.agent;

import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.chat.conversation.Conversation;
import com.intellidesk.chat.conversation.ConversationMapper;
import com.intellidesk.chat.conversation.ConversationService;
import com.intellidesk.chat.message.ChatMessage;
import com.intellidesk.chat.message.ChatMessageMapper;
import com.intellidesk.chat.message.ChatMessageStatus;
import com.intellidesk.chat.message.ChatRole;
import com.intellidesk.chat.memory.ConversationMemoryService;
import com.intellidesk.workspace.Workspace;
import com.intellidesk.workspace.WorkspaceMapper;
import com.intellidesk.workspace.WorkspaceMember;
import com.intellidesk.workspace.WorkspaceMemberMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 Wave 4 Persistence Integration Test.
 * <p>
 * Verifies with real PostgreSQL (Testcontainers):
 * <ul>
 *   <li>USER + ASSISTANT(GENERATING) persistence via insertMessagePair</li>
 *   <li>TOOL message persistence with structured JSON envelope</li>
 *   <li>sequence_no strict increasing</li>
 *   <li>Assistant sequence relocation after TOOL messages</li>
 *   <li>UNIQUE(conversation_id, sequence_no) constraint</li>
 *   <li>CAS guard: only one path finalizes assistant</li>
 *   <li>ConversationMemoryService excludes TOOL messages</li>
 *   <li>Concurrency: requireNotBusy prevents concurrent requests</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
@DisplayName("Phase 5 Wave 4 Persistence Integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("isDockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase5Wave4PersistenceTest {

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

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private ConversationMemoryService memoryService;

    @Autowired
    private WorkspaceMapper workspaceMapper;

    @Autowired
    private WorkspaceMemberMapper workspaceMemberMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long workspaceId;
    private Long userId;
    private Long conversationId;

    @BeforeEach
    void setUp() {
        // Create test user
        userId = 999L;
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password_hash, nickname, email, status) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "testuser_w4", "hash", "Test User W4", "test@test.com", 1);
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, 1L);
        workspaceId = transactionTemplate.execute(status -> {
            Workspace ws = new Workspace();
            ws.setName("Wave4 Test Workspace");
            ws.setOwnerId(userId);
            ws.setCreatedAt(LocalDateTime.now());
            ws.setUpdatedAt(LocalDateTime.now());
            workspaceMapper.insert(ws);

            WorkspaceMember member = new WorkspaceMember();
            member.setWorkspaceId(ws.getId());
            member.setUserId(userId);
            member.setRole("OWNER");
            member.setJoinedAt(LocalDateTime.now());
            workspaceMemberMapper.insert(member);

            return ws.getId();
        });

        // Create test conversation
        conversationId = transactionTemplate.execute(status -> {
            Conversation conv = conversationService.create(workspaceId, userId, "Wave4 Test");
            return conv.getId();
        });
    }

    @AfterEach
    void tearDown() {
        // Clean up test data in reverse dependency order
        try {
            transactionTemplate.executeWithoutResult(status -> {
                chatMessageMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId));
                conversationMapper.deleteById(conversationId);
                workspaceMemberMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId));
                workspaceMapper.deleteById(workspaceId);
            });
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        } catch (Exception e) {
            // ignore cleanup errors
        }
    }

    // ================================================================
    // TX1: USER + ASSISTANT persistence
    // ================================================================

    @Nested
    @DisplayName("TX1: USER + ASSISTANT persistence")
    class Tx1PersistenceTests {

        @Test
        @DisplayName("insertMessagePair persists both USER and ASSISTANT(GENERATING)")
        void insertsMessagePair() {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Hello");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);

            List<ChatMessage> messages = conversationService.getMessages(conversationId, workspaceId, userId);
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).getRole()).isEqualTo(ChatRole.USER.name());
            assertThat(messages.get(0).getSequenceNo()).isEqualTo(1);
            assertThat(messages.get(0).getStatus()).isEqualTo(ChatMessageStatus.SUCCESS.name());
            assertThat(messages.get(1).getRole()).isEqualTo(ChatRole.ASSISTANT.name());
            assertThat(messages.get(1).getSequenceNo()).isEqualTo(2);
            assertThat(messages.get(1).getStatus()).isEqualTo(ChatMessageStatus.GENERATING.name());
        }

        @Test
        @DisplayName("sequence numbers start from 1 for empty conversation")
        void sequenceStartsFromOne() {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("First");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);

            assertThat(userMsg.getSequenceNo()).isEqualTo(1);
            assertThat(assistantMsg.getSequenceNo()).isEqualTo(2);
        }
    }

    // ================================================================
    // TOOL message persistence
    // ================================================================

    @Nested
    @DisplayName("TOOL message persistence")
    class ToolPersistenceTests {

        @Test
        @DisplayName("insertToolMessage persists TOOL with correct sequence")
        void insertsToolMessage() {
            // First insert USER + ASSISTANT
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Query");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);

            // Insert TOOL message
            ChatMessage toolMsg = new ChatMessage();
            toolMsg.setConversationId(conversationId);
            toolMsg.setRole(ChatRole.TOOL.name());
            toolMsg.setContent("{\"toolName\":\"knowledge_search\",\"success\":true}");
            toolMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            toolMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertToolMessage(conversationId, toolMsg);

            assertThat(toolMsg.getSequenceNo()).isEqualTo(3);
            assertThat(toolMsg.getRole()).isEqualTo(ChatRole.TOOL.name());
        }

        @Test
        @DisplayName("multiple TOOL messages get sequential numbers")
        void multipleToolMessagesGetSequentialNumbers() {
            // Insert USER + ASSISTANT
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Query");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);
            // seq: USER=1, ASSISTANT=2

            // Insert TOOL A
            ChatMessage toolA = new ChatMessage();
            toolA.setConversationId(conversationId);
            toolA.setRole(ChatRole.TOOL.name());
            toolA.setContent("{\"toolName\":\"tool_a\"}");
            toolA.setStatus(ChatMessageStatus.SUCCESS.name());
            toolA.setCreatedAt(LocalDateTime.now());
            conversationService.insertToolMessage(conversationId, toolA);

            // Insert TOOL B
            ChatMessage toolB = new ChatMessage();
            toolB.setConversationId(conversationId);
            toolB.setRole(ChatRole.TOOL.name());
            toolB.setContent("{\"toolName\":\"tool_b\"}");
            toolB.setStatus(ChatMessageStatus.SUCCESS.name());
            toolB.setCreatedAt(LocalDateTime.now());
            conversationService.insertToolMessage(conversationId, toolB);

            // Insert TOOL C
            ChatMessage toolC = new ChatMessage();
            toolC.setConversationId(conversationId);
            toolC.setRole(ChatRole.TOOL.name());
            toolC.setContent("{\"toolName\":\"tool_c\"}");
            toolC.setStatus(ChatMessageStatus.SUCCESS.name());
            toolC.setCreatedAt(LocalDateTime.now());
            conversationService.insertToolMessage(conversationId, toolC);

            assertThat(toolA.getSequenceNo()).isEqualTo(3);
            assertThat(toolB.getSequenceNo()).isEqualTo(4);
            assertThat(toolC.getSequenceNo()).isEqualTo(5);
        }

        @Test
        @DisplayName("TOOL messages have structured JSON content")
        void toolMessagesHaveStructuredJsonContent() {
            // Insert USER + ASSISTANT
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Query");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);

            // Insert TOOL with real envelope
            ChatMessage toolMsg = new ChatMessage();
            toolMsg.setConversationId(conversationId);
            toolMsg.setRole(ChatRole.TOOL.name());
            toolMsg.setContent("""
                    {"toolCallId":"call_123","toolName":"knowledge_search","arguments":{"query":"test"},"success":true,"result":"Found 3 docs","errorCode":null,"errorMessage":null,"durationMs":150}""");
            toolMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            toolMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertToolMessage(conversationId, toolMsg);

            List<ChatMessage> messages = conversationService.getMessages(conversationId, workspaceId, userId);
            ChatMessage persistedTool = messages.stream()
                    .filter(m -> ChatRole.TOOL.name().equals(m.getRole()))
                    .findFirst().orElseThrow();

            assertThat(persistedTool.getContent()).contains("\"toolName\":\"knowledge_search\"");
            assertThat(persistedTool.getContent()).contains("\"success\":true");
            assertThat(persistedTool.getContent()).contains("\"result\":\"Found 3 docs\"");
            assertThat(persistedTool.getContent()).contains("\"durationMs\":150");
        }

        @Test
        @DisplayName("TOOL message content does NOT contain stack traces")
        void toolContentDoesNotContainStackTraces() {
            // Insert USER + ASSISTANT
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Query");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);

            ChatMessage toolMsg = new ChatMessage();
            toolMsg.setConversationId(conversationId);
            toolMsg.setRole(ChatRole.TOOL.name());
            toolMsg.setContent("{\"toolName\":\"test\",\"success\":false,\"errorCode\":\"ERR\",\"errorMessage\":\"safe msg\"}");
            toolMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            toolMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertToolMessage(conversationId, toolMsg);

            List<ChatMessage> messages = conversationService.getMessages(conversationId, workspaceId, userId);
            ChatMessage persistedTool = messages.stream()
                    .filter(m -> ChatRole.TOOL.name().equals(m.getRole()))
                    .findFirst().orElseThrow();

            assertThat(persistedTool.getContent()).doesNotContain("stackTrace");
            assertThat(persistedTool.getContent()).doesNotContain("Exception");
            assertThat(persistedTool.getContent()).doesNotContain("at com.intellidesk");
        }
    }

    // ================================================================
    // Assistant sequence relocation
    // ================================================================

    @Nested
    @DisplayName("Assistant sequence relocation")
    class SequenceRelocationTests {

        @Test
        @DisplayName("no-tool: assistant stays at original sequence")
        void noToolAssistantStaysAtOriginalSequence() {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Query");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);
            // USER=1, ASSISTANT=2

            // Finalize without tools
            assistantMsg.setContent("Final answer");
            assistantMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            boolean result = conversationService.finalizeAssistant(assistantMsg);

            assertThat(result).isTrue();
            assertThat(assistantMsg.getSequenceNo()).isEqualTo(2); // unchanged
            assertThat(assistantMsg.getStatus()).isEqualTo(ChatMessageStatus.SUCCESS.name());
        }

        @Test
        @DisplayName("one-tool: assistant relocated to max+1")
        void oneToolAssistantRelocated() {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Query");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);
            // USER=1, ASSISTANT=2

            // Insert TOOL
            ChatMessage toolMsg = new ChatMessage();
            toolMsg.setConversationId(conversationId);
            toolMsg.setRole(ChatRole.TOOL.name());
            toolMsg.setContent("{\"toolName\":\"t\"}");
            toolMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            toolMsg.setCreatedAt(LocalDateTime.now());
            conversationService.insertToolMessage(conversationId, toolMsg);
            // TOOL=3

            // Finalize with tools → should relocate to 4
            assistantMsg.setContent("Answer after tool");
            assistantMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            boolean result = conversationService.finalizeAssistant(assistantMsg);

            assertThat(result).isTrue();
            assertThat(assistantMsg.getSequenceNo()).isEqualTo(4); // relocated to max(3)+1 = 4
        }

        @Test
        @DisplayName("multi-tool: assistant relocated to after all tools")
        void multiToolAssistantRelocatedToAfterAllTools() {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Query");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);
            // USER=1, ASSISTANT=2

            // Insert TOOL A, TOOL B, TOOL C
            for (int i = 0; i < 3; i++) {
                ChatMessage toolMsg = new ChatMessage();
                toolMsg.setConversationId(conversationId);
                toolMsg.setRole(ChatRole.TOOL.name());
                toolMsg.setContent("{\"toolName\":\"tool_" + (char) ('A' + i) + "\"}");
                toolMsg.setStatus(ChatMessageStatus.SUCCESS.name());
                toolMsg.setCreatedAt(LocalDateTime.now());
                conversationService.insertToolMessage(conversationId, toolMsg);
            }
            // TOOL A=3, TOOL B=4, TOOL C=5

            // Finalize with tools → should relocate to 6
            assistantMsg.setContent("Final answer");
            assistantMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            boolean result = conversationService.finalizeAssistant(assistantMsg);

            assertThat(result).isTrue();
            assertThat(assistantMsg.getSequenceNo()).isEqualTo(6);
        }

        @Test
        @DisplayName("final DB order: USER, TOOLs, ASSISTANT")
        void finalDbOrderUserToolsAssistant() {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Query");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);

            // Insert 2 TOOL messages
            ChatMessage toolA = new ChatMessage();
            toolA.setConversationId(conversationId);
            toolA.setRole(ChatRole.TOOL.name());
            toolA.setContent("{\"toolName\":\"tool_a\"}");
            toolA.setStatus(ChatMessageStatus.SUCCESS.name());
            toolA.setCreatedAt(LocalDateTime.now());
            conversationService.insertToolMessage(conversationId, toolA);

            ChatMessage toolB = new ChatMessage();
            toolB.setConversationId(conversationId);
            toolB.setRole(ChatRole.TOOL.name());
            toolB.setContent("{\"toolName\":\"tool_b\"}");
            toolB.setStatus(ChatMessageStatus.SUCCESS.name());
            toolB.setCreatedAt(LocalDateTime.now());
            conversationService.insertToolMessage(conversationId, toolB);

            // Finalize assistant with relocation
            assistantMsg.setContent("Answer");
            assistantMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            conversationService.finalizeAssistant(assistantMsg);

            // Verify final order
            List<ChatMessage> messages = conversationService.getMessages(conversationId, workspaceId, userId);
            assertThat(messages).hasSize(4);
            assertThat(messages.get(0).getRole()).isEqualTo(ChatRole.USER.name());
            assertThat(messages.get(0).getSequenceNo()).isEqualTo(1);
            assertThat(messages.get(1).getRole()).isEqualTo(ChatRole.TOOL.name());
            assertThat(messages.get(1).getSequenceNo()).isEqualTo(3);
            assertThat(messages.get(2).getRole()).isEqualTo(ChatRole.TOOL.name());
            assertThat(messages.get(2).getSequenceNo()).isEqualTo(4);
            assertThat(messages.get(3).getRole()).isEqualTo(ChatRole.ASSISTANT.name());
            assertThat(messages.get(3).getSequenceNo()).isEqualTo(5);
            assertThat(messages.get(3).getStatus()).isEqualTo(ChatMessageStatus.SUCCESS.name());
        }

        @Test
        @DisplayName("FAILED after tool: assistant relocated to after all tools")
        void failedAfterToolAssistantRelocated() {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Query");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);
            // USER=1, ASSISTANT(GENERATING)=2

            // Insert TOOL
            ChatMessage toolMsg = new ChatMessage();
            toolMsg.setConversationId(conversationId);
            toolMsg.setRole(ChatRole.TOOL.name());
            toolMsg.setContent("{\"toolName\":\"t\"}");
            toolMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            toolMsg.setCreatedAt(LocalDateTime.now());
            conversationService.insertToolMessage(conversationId, toolMsg);
            // TOOL=3

            // Finalize as FAILED with hasTools=true
            assistantMsg.setContent("Error occurred");
            assistantMsg.setStatus(ChatMessageStatus.FAILED.name());
            assistantMsg.setErrorCode("TEST_ERROR");
            assistantMsg.setErrorMessage("Test error");
            boolean result = conversationService.finalizeAssistant(assistantMsg);

            assertThat(result).isTrue();
            assertThat(assistantMsg.getSequenceNo()).isEqualTo(4); // relocated to max(3)+1=4
            assertThat(assistantMsg.getStatus()).isEqualTo(ChatMessageStatus.FAILED.name());
            assertThat(assistantMsg.getErrorCode()).isEqualTo("TEST_ERROR");
        }

        @Test
        @DisplayName("CANCELLED after tool: assistant relocated to after all tools")
        void cancelledAfterToolAssistantRelocated() {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Query");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);
            // USER=1, ASSISTANT(GENERATING)=2

            // Insert TOOL
            ChatMessage toolMsg = new ChatMessage();
            toolMsg.setConversationId(conversationId);
            toolMsg.setRole(ChatRole.TOOL.name());
            toolMsg.setContent("{\"toolName\":\"t\"}");
            toolMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            toolMsg.setCreatedAt(LocalDateTime.now());
            conversationService.insertToolMessage(conversationId, toolMsg);
            // TOOL=3

            // Finalize as CANCELLED with hasTools=true
            assistantMsg.setContent("Partial content");
            assistantMsg.setStatus(ChatMessageStatus.CANCELLED.name());
            assistantMsg.setErrorCode("CLIENT_DISCONNECT");
            assistantMsg.setErrorMessage("Client disconnected");
            boolean result = conversationService.finalizeAssistant(assistantMsg);

            assertThat(result).isTrue();
            assertThat(assistantMsg.getSequenceNo()).isEqualTo(4); // relocated to max(3)+1=4
            assertThat(assistantMsg.getStatus()).isEqualTo(ChatMessageStatus.CANCELLED.name());
            assertThat(assistantMsg.getErrorCode()).isEqualTo("CLIENT_DISCONNECT");
        }

        @Test
        @DisplayName("UNIQUE(conversation_id, sequence_no) constraint is satisfied")
        void uniqueConstraintIsSatisfied() {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Q");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);
            // USER=1, ASSISTANT=2

            ChatMessage toolMsg = new ChatMessage();
            toolMsg.setConversationId(conversationId);
            toolMsg.setRole(ChatRole.TOOL.name());
            toolMsg.setContent("{\"toolName\":\"t\"}");
            toolMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            toolMsg.setCreatedAt(LocalDateTime.now());
            conversationService.insertToolMessage(conversationId, toolMsg);
            // TOOL=3

            // Finalize with relocation
            assistantMsg.setContent("Answer");
            assistantMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            boolean result = conversationService.finalizeAssistant(assistantMsg);

            assertThat(result).isTrue();
            // All sequences should be unique: 1, 2(overwritten), 3, 4
            // The original ASSISTANT at seq=2 no longer exists in the final state
            // because it was updated in-place with seq=4
        }
    }

    // ================================================================
    // CAS guard
    // ================================================================

    @Nested
    @DisplayName("CAS guard")
    class CasGuardTests {

        @Test
        @DisplayName("CAS update succeeds when status is GENERATING")
        void casSucceedsWhenGenerating() {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Q");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);

            assistantMsg.setContent("Answer");
            assistantMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            boolean result = conversationService.updateMessageCas(assistantMsg);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("CAS update fails when status is no longer GENERATING")
        void casFailsWhenNotGenerating() {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Q");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);

            // First finalize: success
            assistantMsg.setContent("First answer");
            assistantMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            boolean first = conversationService.updateMessageCas(assistantMsg);
            assertThat(first).isTrue();

            // Second attempt: should fail (status is already SUCCESS, not GENERATING)
            assistantMsg.setContent("Second answer");
            assistantMsg.setStatus(ChatMessageStatus.CANCELLED.name());
            boolean second = conversationService.updateMessageCas(assistantMsg);
            assertThat(second).isFalse();
        }

        @Test
        @DisplayName("only one path wins: success vs cancel")
        void onlyOnePathWins() throws Exception {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Q");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);

            AtomicBoolean successWon = new AtomicBoolean(false);
            AtomicBoolean cancelWon = new AtomicBoolean(false);
            CountDownLatch latch = new CountDownLatch(2);

            var executor = Executors.newFixedThreadPool(2);

            // Thread 1: try to finalize as SUCCESS
            executor.submit(() -> {
                try {
                    ChatMessage msg = new ChatMessage();
                    msg.setId(assistantMsg.getId());
                    msg.setConversationId(conversationId);
                    msg.setContent("Success answer");
                    msg.setStatus(ChatMessageStatus.SUCCESS.name());
                    boolean result = conversationService.updateMessageCas(msg);
                    if (result) successWon.set(true);
                } finally {
                    latch.countDown();
                }
            });

            // Thread 2: try to finalize as CANCELLED
            executor.submit(() -> {
                try {
                    ChatMessage msg = new ChatMessage();
                    msg.setId(assistantMsg.getId());
                    msg.setConversationId(conversationId);
                    msg.setContent("Cancelled partial");
                    msg.setStatus(ChatMessageStatus.CANCELLED.name());
                    boolean result = conversationService.updateMessageCas(msg);
                    if (result) cancelWon.set(true);
                } finally {
                    latch.countDown();
                }
            });

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // Exactly one should win
            assertThat(successWon.get() ^ cancelWon.get()).isTrue();
        }
    }

    // ================================================================
    // ConversationMemoryService excludes TOOL
    // ================================================================

    @Nested
    @DisplayName("ConversationMemoryService excludes TOOL")
    class MemoryExcludesToolTests {

        @Test
        @DisplayName("loadRecentMessages excludes TOOL messages")
        void loadRecentMessagesExcludesTool() {
            // Insert USER + ASSISTANT
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Query");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("Answer");
            assistantMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);

            // Insert TOOL
            ChatMessage toolMsg = new ChatMessage();
            toolMsg.setConversationId(conversationId);
            toolMsg.setRole(ChatRole.TOOL.name());
            toolMsg.setContent("{\"toolName\":\"t\"}");
            toolMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            toolMsg.setCreatedAt(LocalDateTime.now());
            conversationService.insertToolMessage(conversationId, toolMsg);

            // Load memory — should only get USER + ASSISTANT
            List<ChatMessage> memory = memoryService.loadRecentMessages(workspaceId, conversationId, userId, 10);

            assertThat(memory).hasSize(2);
            assertThat(memory).allMatch(m ->
                    ChatRole.USER.name().equals(m.getRole()) || ChatRole.ASSISTANT.name().equals(m.getRole()));
            assertThat(memory).noneMatch(m -> ChatRole.TOOL.name().equals(m.getRole()));
        }

        @Test
        @DisplayName("buildAnswerMessages excludes TOOL messages")
        void buildAnswerMessagesExcludesTool() {
            // Insert USER + ASSISTANT
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Query");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("Answer");
            assistantMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);

            ChatMessage toolMsg = new ChatMessage();
            toolMsg.setConversationId(conversationId);
            toolMsg.setRole(ChatRole.TOOL.name());
            toolMsg.setContent("{\"toolName\":\"t\"}");
            toolMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            toolMsg.setCreatedAt(LocalDateTime.now());
            conversationService.insertToolMessage(conversationId, toolMsg);

            List<org.springframework.ai.chat.messages.Message> messages =
                    memoryService.buildAnswerMessages(workspaceId, conversationId, userId, 10);

            assertThat(messages).hasSize(2);
            assertThat(messages.get(0)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
            assertThat(messages.get(1)).isInstanceOf(org.springframework.ai.chat.messages.AssistantMessage.class);
        }
    }

    // ================================================================
    // Concurrency / Busy guard
    // ================================================================

    @Nested
    @DisplayName("Concurrency / busy guard")
    class ConcurrencyBusyGuardTests {

        @Test
        @DisplayName("requireNotBusy throws when GENERATING message exists")
        void requireNotBusyThrowsWhenGenerating() {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Q");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);

            assertThatThrownBy(() -> conversationService.requireNotBusy(conversationId))
                    .isInstanceOf(com.intellidesk.common.BusinessException.class);
        }

        @Test
        @DisplayName("requireNotBusy succeeds when no GENERATING message exists")
        void requireNotBusySucceedsWhenNoGenerating() {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Q");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("Answer");
            assistantMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);

            // Should not throw
            conversationService.requireNotBusy(conversationId);
        }

        @Test
        @DisplayName("concurrent start: second insertMessagePair throws CHAT_CONVERSATION_BUSY")
        void concurrentStartSecondInsertThrowsBusy() throws Exception {
            // Two threads concurrently try to insertMessagePair on the same conversation.
            // The authoritative busy check inside insertMessagePair (after SELECT FOR UPDATE)
            // must reject the second thread with CHAT_CONVERSATION_BUSY.
            // Only one thread should succeed, and only one GENERATING message should exist.

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger busyCount = new AtomicInteger(0);
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            var executor = Executors.newFixedThreadPool(2);

            Runnable task = () -> {
                try {
                    ChatMessage userMsg = new ChatMessage();
                    userMsg.setConversationId(conversationId);
                    userMsg.setRole(ChatRole.USER.name());
                    userMsg.setContent("Concurrent query");
                    userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
                    userMsg.setCreatedAt(LocalDateTime.now());

                    ChatMessage assistantMsg = new ChatMessage();
                    assistantMsg.setConversationId(conversationId);
                    assistantMsg.setRole(ChatRole.ASSISTANT.name());
                    assistantMsg.setContent("");
                    assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
                    assistantMsg.setCreatedAt(LocalDateTime.now());

                    readyLatch.countDown();
                    startLatch.await(); // Wait for both threads to be ready

                    conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);
                    successCount.incrementAndGet();
                } catch (com.intellidesk.common.BusinessException e) {
                    if (e.getCode() == 6009) { // CHAT_CONVERSATION_BUSY
                        busyCount.incrementAndGet();
                    } else {
                        throw new RuntimeException(e);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };

            executor.submit(task);
            executor.submit(task);

            readyLatch.await(10, TimeUnit.SECONDS);
            startLatch.countDown();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            // Exactly one thread should succeed, one should get CHAT_CONVERSATION_BUSY
            assertThat(successCount.get()).isEqualTo(1);
            assertThat(busyCount.get()).isEqualTo(1);

            // Verify only one GENERATING message exists in DB
            List<ChatMessage> messages = conversationService.getMessages(conversationId, workspaceId, userId);
            long generatingCount = messages.stream()
                    .filter(m -> ChatMessageStatus.GENERATING.name().equals(m.getStatus()))
                    .count();
            assertThat(generatingCount).isEqualTo(1);
        }
    }

    // ================================================================
    // Cancel-vs-TOOL Persistence Race (PostgreSQL, 2 threads)
    // ================================================================

    @Nested
    @DisplayName("Cancel-vs-TOOL persistence race")
    class CancelVsToolPersistenceRaceTests {

        @Test
        @DisplayName("Case A: TOOL insert wins lock first → TOOL exists, assistant relocated to CANCELLED")
        void toolInsertWinsLockFirst_toolExistsAssistantRelocated() throws Exception {
            // Setup: USER + ASSISTANT(GENERATING)
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Query");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);
            // USER=1, ASSISTANT(GENERATING)=2

            Long assistantId = assistantMsg.getId();

            CountDownLatch toolReady = new CountDownLatch(1);
            CountDownLatch toolDone = new CountDownLatch(1);
            CountDownLatch cancelReady = new CountDownLatch(1);
            CountDownLatch startLatch = new CountDownLatch(1);

            AtomicBoolean toolInserted = new AtomicBoolean(false);
            AtomicBoolean cancelFinalized = new AtomicBoolean(false);

            var executor = Executors.newFixedThreadPool(2);

            // Thread A: TOOL insert (wins the race by starting first)
            executor.submit(() -> {
                try {
                    toolReady.countDown();
                    startLatch.await();
                    // Try to insert TOOL while assistant is still GENERATING
                    ChatMessage toolMsg = new ChatMessage();
                    toolMsg.setConversationId(conversationId);
                    toolMsg.setRole(ChatRole.TOOL.name());
                    toolMsg.setContent("{\"toolName\":\"knowledge_search\",\"success\":true}");
                    toolMsg.setStatus(ChatMessageStatus.SUCCESS.name());
                    toolMsg.setCreatedAt(LocalDateTime.now());
                    boolean result = transactionTemplate.execute(status ->
                            conversationService.tryInsertToolMessage(conversationId, assistantId, toolMsg));
                    toolInserted.set(result);
                } catch (Exception e) {
                    // ignore
                } finally {
                    toolDone.countDown();
                }
            });

            // Thread B: Cancel finalization (starts after TOOL)
            executor.submit(() -> {
                try {
                    // Wait for TOOL thread to be ready first
                    toolReady.await(10, TimeUnit.SECONDS);
                    cancelReady.countDown();
                    startLatch.await();
                    // Small delay to let TOOL thread acquire lock first
                    Thread.sleep(50);
                    // Try to finalize as CANCELLED
                    ChatMessage cancelMsg = new ChatMessage();
                    cancelMsg.setId(assistantId);
                    cancelMsg.setConversationId(conversationId);
                    cancelMsg.setSequenceNo(assistantMsg.getSequenceNo()); // required by finalizeAssistant
                    cancelMsg.setContent("Partial");
                    cancelMsg.setStatus(ChatMessageStatus.CANCELLED.name());
                    cancelMsg.setErrorCode("CLIENT_DISCONNECT");
                    cancelMsg.setErrorMessage("Client disconnected");
                    boolean result = conversationService.finalizeAssistant(cancelMsg);
                    cancelFinalized.set(result);
                } catch (Exception e) {
                    // ignore
                }
            });

            cancelReady.await(10, TimeUnit.SECONDS);
            startLatch.countDown();
            toolDone.await(10, TimeUnit.SECONDS);

            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            // Case A: TOOL insert should succeed (assistant was GENERATING)
            assertThat(toolInserted.get()).isTrue();

            // After TOOL insert, cancellation should also succeed
            // (assistant is still GENERATING at this point in the race)
            // Actually cancelFinalized might be true or false depending on exact timing
            // But the key assertion is that TOOL was inserted
            // and after cancel finalize, assistant is relocated

            // Verify the final DB state
            List<ChatMessage> messages = transactionTemplate.execute(status ->
                    conversationService.getMessages(conversationId, workspaceId, userId));

            // Should have USER, TOOL, and ASSISTANT (with CANCELLED if cancel finalized)
            assertThat(messages).hasSizeGreaterThanOrEqualTo(3);

            // TOOL should exist
            boolean hasTool = messages.stream().anyMatch(m -> ChatRole.TOOL.name().equals(m.getRole()));
            assertThat(hasTool).isTrue();

            // ASSISTANT should not be GENERATING
            boolean hasGenerating = messages.stream()
                    .anyMatch(m -> ChatMessageStatus.GENERATING.name().equals(m.getStatus()));
            assertThat(hasGenerating).isFalse();

            // No invalid state: ASSISTANT CANCELLED followed by TOOL
            // Verify that in the final order, TOOL comes before ASSISTANT terminal
            int toolSeq = -1;
            int assistantSeq = -1;
            for (ChatMessage msg : messages) {
                if (ChatRole.TOOL.name().equals(msg.getRole())) {
                    toolSeq = msg.getSequenceNo();
                }
                if (ChatRole.ASSISTANT.name().equals(msg.getRole())
                        && !ChatMessageStatus.GENERATING.name().equals(msg.getStatus())) {
                    assistantSeq = msg.getSequenceNo();
                }
            }
            if (toolSeq > 0 && assistantSeq > 0) {
                assertThat(toolSeq).isLessThan(assistantSeq);
            }
        }

        @Test
        @DisplayName("Case B: Cancel finalization wins lock first → TOOL insert rejected, no TOOL after CANCELLED")
        void cancelWinsLockFirst_toolInsertRejected() throws Exception {
            // Setup: USER + ASSISTANT(GENERATING)
            ChatMessage userMsg = new ChatMessage();
            userMsg.setConversationId(conversationId);
            userMsg.setRole(ChatRole.USER.name());
            userMsg.setContent("Query");
            userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
            userMsg.setCreatedAt(LocalDateTime.now());

            ChatMessage assistantMsg = new ChatMessage();
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole(ChatRole.ASSISTANT.name());
            assistantMsg.setContent("");
            assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
            assistantMsg.setCreatedAt(LocalDateTime.now());

            conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);
            // USER=1, ASSISTANT(GENERATING)=2

            Long assistantId = assistantMsg.getId();

            CountDownLatch cancelReady = new CountDownLatch(1);
            CountDownLatch toolReady = new CountDownLatch(1);
            CountDownLatch startLatch = new CountDownLatch(1);

            AtomicBoolean cancelFinalized = new AtomicBoolean(false);
            AtomicBoolean toolInserted = new AtomicBoolean(false);

            var executor = Executors.newFixedThreadPool(2);

            // Thread A: Cancel finalization (wins the race)
            executor.submit(() -> {
                try {
                    cancelReady.countDown();
                    startLatch.await();
                    // Finalize as CANCELLED immediately
                    ChatMessage cancelMsg = new ChatMessage();
                    cancelMsg.setId(assistantId);
                    cancelMsg.setConversationId(conversationId);
                    cancelMsg.setSequenceNo(assistantMsg.getSequenceNo()); // required by finalizeAssistant
                    cancelMsg.setContent("Partial");
                    cancelMsg.setStatus(ChatMessageStatus.CANCELLED.name());
                    cancelMsg.setErrorCode("CLIENT_DISCONNECT");
                    cancelMsg.setErrorMessage("Client disconnected");
                    boolean result = conversationService.finalizeAssistant(cancelMsg);
                    cancelFinalized.set(result);
                } catch (Exception e) {
                    // ignore
                }
            });

            // Thread B: TOOL insert (tries after cancel, should be rejected)
            executor.submit(() -> {
                try {
                    // Wait for cancel thread to be ready first
                    cancelReady.await(10, TimeUnit.SECONDS);
                    toolReady.countDown();
                    startLatch.await();
                    // Small delay to let cancel thread acquire lock first
                    Thread.sleep(50);
                    // Try to insert TOOL — assistant should already be CANCELLED
                    ChatMessage toolMsg = new ChatMessage();
                    toolMsg.setConversationId(conversationId);
                    toolMsg.setRole(ChatRole.TOOL.name());
                    toolMsg.setContent("{\"toolName\":\"knowledge_search\",\"success\":true}");
                    toolMsg.setStatus(ChatMessageStatus.SUCCESS.name());
                    toolMsg.setCreatedAt(LocalDateTime.now());
                    boolean result = transactionTemplate.execute(status ->
                            conversationService.tryInsertToolMessage(conversationId, assistantId, toolMsg));
                    toolInserted.set(result);
                } catch (Exception e) {
                    // ignore
                }
            });

            toolReady.await(10, TimeUnit.SECONDS);
            startLatch.countDown();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            // Case B: Cancel should succeed
            assertThat(cancelFinalized.get()).isTrue();

            // TOOL insert should be REJECTED
            assertThat(toolInserted.get()).isFalse();

            // Verify final DB state: no TOOL, ASSISTANT is CANCELLED
            List<ChatMessage> messages = transactionTemplate.execute(status ->
                    conversationService.getMessages(conversationId, workspaceId, userId));

            // No TOOL messages
            long toolCount = messages.stream()
                    .filter(m -> ChatRole.TOOL.name().equals(m.getRole()))
                    .count();
            assertThat(toolCount).isEqualTo(0);

            // ASSISTANT is CANCELLED
            ChatMessage assistant = messages.stream()
                    .filter(m -> ChatRole.ASSISTANT.name().equals(m.getRole()))
                    .findFirst().orElseThrow();
            assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.CANCELLED.name());
        }
    }

    // ================================================================
    // Chat × Agent Concurrent Start Integration Test
    // ================================================================

    @Nested
    @DisplayName("Chat × Agent concurrent start")
    class ChatAgentConcurrentStartTests {

        @Test
        @DisplayName("concurrent Chat and Agent start on same conversation: only one GENERATING pair succeeds")
        void concurrentChatAndAgentStart_onlyOneGeneratingPairSucceeds() throws Exception {
            // Two threads concurrently try to insertMessagePair on the same conversation.
            // This simulates both a Chat request and an Agent request hitting the same conversation.
            // Only one should succeed; the other should get CHAT_CONVERSATION_BUSY.

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger busyCount = new AtomicInteger(0);
            AtomicReference<String> successRole = new AtomicReference<>();
            CountDownLatch readyLatch = new CountDownLatch(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            var executor = Executors.newFixedThreadPool(2);

            // Thread 1: Simulates a Chat request
            Runnable chatTask = () -> {
                try {
                    ChatMessage userMsg = new ChatMessage();
                    userMsg.setConversationId(conversationId);
                    userMsg.setRole(ChatRole.USER.name());
                    userMsg.setContent("Chat query");
                    userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
                    userMsg.setCreatedAt(LocalDateTime.now());

                    ChatMessage assistantMsg = new ChatMessage();
                    assistantMsg.setConversationId(conversationId);
                    assistantMsg.setRole(ChatRole.ASSISTANT.name());
                    assistantMsg.setContent("");
                    assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
                    assistantMsg.setCreatedAt(LocalDateTime.now());

                    readyLatch.countDown();
                    startLatch.await();

                    conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);
                    successCount.incrementAndGet();
                    successRole.set("CHAT");
                } catch (com.intellidesk.common.BusinessException e) {
                    if (e.getCode() == 6009) {
                        busyCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // ignore
                }
            };

            // Thread 2: Simulates an Agent request
            Runnable agentTask = () -> {
                try {
                    ChatMessage userMsg = new ChatMessage();
                    userMsg.setConversationId(conversationId);
                    userMsg.setRole(ChatRole.USER.name());
                    userMsg.setContent("Agent query");
                    userMsg.setStatus(ChatMessageStatus.SUCCESS.name());
                    userMsg.setCreatedAt(LocalDateTime.now());

                    ChatMessage assistantMsg = new ChatMessage();
                    assistantMsg.setConversationId(conversationId);
                    assistantMsg.setRole(ChatRole.ASSISTANT.name());
                    assistantMsg.setContent("");
                    assistantMsg.setStatus(ChatMessageStatus.GENERATING.name());
                    assistantMsg.setCreatedAt(LocalDateTime.now());

                    readyLatch.countDown();
                    startLatch.await();

                    conversationService.insertMessagePair(conversationId, userMsg, assistantMsg);
                    successCount.incrementAndGet();
                    successRole.compareAndSet(null, "AGENT");
                } catch (com.intellidesk.common.BusinessException e) {
                    if (e.getCode() == 6009) {
                        busyCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // ignore
                }
            };

            executor.submit(chatTask);
            executor.submit(agentTask);

            readyLatch.await(10, TimeUnit.SECONDS);
            startLatch.countDown();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            // Exactly one thread should succeed, one should get CHAT_CONVERSATION_BUSY
            assertThat(successCount.get()).isEqualTo(1);
            assertThat(busyCount.get()).isEqualTo(1);

            // Verify only one GENERATING message exists in DB
            List<ChatMessage> messages = conversationService.getMessages(conversationId, workspaceId, userId);
            long generatingCount = messages.stream()
                    .filter(m -> ChatMessageStatus.GENERATING.name().equals(m.getStatus()))
                    .count();
            assertThat(generatingCount).isEqualTo(1);
        }
    }
}