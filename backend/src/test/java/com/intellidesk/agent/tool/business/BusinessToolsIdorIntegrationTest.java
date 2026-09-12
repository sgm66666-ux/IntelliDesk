package com.intellidesk.agent.tool.business;

import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.agent.tool.AgentToolExecutionResult;
import com.intellidesk.agent.tool.ToolExecutionContext;
import com.intellidesk.document.DocumentService;
import com.intellidesk.document.dto.DocumentResponse;
import com.intellidesk.knowledge.KnowledgeBase;
import com.intellidesk.knowledge.KnowledgeBaseService;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 Wave 3 — Real IDOR Integration Test.
 * <p>
 * Uses real KnowledgeBaseService, DocumentService, WorkspaceAuthorizationService,
 * and the 5 business tools. All authorization is real via PostgreSQL.
 * <p>
 * No Mockito mocking of services or resolvers.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
@DisplayName("Business Tools Real IDOR Integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("isDockerAvailable")
class BusinessToolsIdorIntegrationTest {

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
        if (postgres != null) postgres.stop();
    }

    // ================================================================
    // REAL beans — no Mockito
    // ================================================================

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private KnowledgeBaseService knowledgeBaseService;       // REAL
    @Autowired private DocumentService documentService;                 // REAL
    @Autowired private WorkspaceAuthorizationService authService;       // REAL

    // ================================================================
    // Test data constants
    // ================================================================

    private static final Long WS_A = 1L;
    private static final Long WS_B = 2L;
    private static final Long OWNER_A = 1L;
    private static final Long MEMBER_A = 2L;
    private static final Long OUTSIDER = 999L;
    private static final Long KB_A = 1L;
    private static final Long KB_B = 2L;
    private static final Long DOC_A = 1L;
    private static final Long DOC_B = 2L;

    private ToolExecutionContext ownerCtxA;
    private ToolExecutionContext memberCtxA;
    private ToolExecutionContext outsiderCtx;
    private ToolExecutionContext ctxB;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker not available");

        ownerCtxA = new ToolExecutionContext(OWNER_A, WS_A, 300L, "trace-owner");
        memberCtxA = new ToolExecutionContext(MEMBER_A, WS_A, 300L, "trace-member");
        outsiderCtx = new ToolExecutionContext(OUTSIDER, WS_A, 300L, "trace-outsider");
        ctxB = new ToolExecutionContext(OWNER_A, WS_B, 300L, "trace-cross-ws");

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
                OWNER_A, "owner-a", "hash", "owner@test.com", 1);
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password_hash, email, status) VALUES (?, ?, ?, ?, ?)",
                MEMBER_A, "member-a", "hash", "member@test.com", 1);
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password_hash, email, status) VALUES (?, ?, ?, ?, ?)",
                OUTSIDER, "outsider", "hash", "outsider@test.com", 1);

        // ---- Workspace A ----
        jdbcTemplate.update("INSERT INTO workspace (id, name, owner_id) VALUES (?, ?, ?)",
                WS_A, "Workspace A", OWNER_A);
        jdbcTemplate.update("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, ?)",
                WS_A, OWNER_A, "OWNER");
        jdbcTemplate.update("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, ?)",
                WS_A, MEMBER_A, "MEMBER");

        // ---- Workspace B ----
        jdbcTemplate.update("INSERT INTO workspace (id, name, owner_id) VALUES (?, ?, ?)",
                WS_B, "Workspace B", OWNER_A);
        // OWNER_A is NOT a member of Workspace B (no member record)

        // ---- KB A (in WS A) ----
        jdbcTemplate.update("INSERT INTO knowledge_base (id, workspace_id, name, description, status, chunk_strategy, chunk_size, chunk_overlap, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                KB_A, WS_A, "KB A", "Knowledge Base A", "ACTIVE", "RECURSIVE", 1000, 150, OWNER_A);

        // ---- KB B (in WS B) ----
        jdbcTemplate.update("INSERT INTO knowledge_base (id, workspace_id, name, description, status, chunk_strategy, chunk_size, chunk_overlap, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                KB_B, WS_B, "KB B", "Knowledge Base B", "ACTIVE", "RECURSIVE", 1000, 150, OWNER_A);

        // ---- Document A (in KB A, WS A) ----
        jdbcTemplate.update(
                "INSERT INTO document (id, knowledge_base_id, original_file_name, file_extension, content_type, file_size, checksum_sha256, bucket_name, object_key, status, chunk_strategy, chunk_size, chunk_overlap, parser_metadata, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                DOC_A, KB_A, "doc-a.pdf", "pdf", "application/pdf", 1024L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "intellidesk-documents", "key-a", "COMPLETED",
                "RECURSIVE", 1000, 150, "{}", OWNER_A);

        // ---- Document B (in KB B, WS B) ----
        jdbcTemplate.update(
                "INSERT INTO document (id, knowledge_base_id, original_file_name, file_extension, content_type, file_size, checksum_sha256, bucket_name, object_key, status, chunk_strategy, chunk_size, chunk_overlap, parser_metadata, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                DOC_B, KB_B, "doc-b.pdf", "pdf", "application/pdf", 1024L,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "intellidesk-documents", "key-b", "COMPLETED",
                "RECURSIVE", 1000, 150, "{}", OWNER_A);
    }

    // ================================================================
    // knowledge_base_detail IDOR
    // ================================================================

    @Nested
    @DisplayName("knowledge_base_detail - Real IDOR")
    class KnowledgeBaseDetailIdor {

        @Test
        @DisplayName("Owner can access KB A")
        void ownerCanAccess() {
            KnowledgeBaseDetailTool tool = new KnowledgeBaseDetailTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ownerCtxA, new KnowledgeBaseDetailArguments(KB_A));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("KB A");
        }

        @Test
        @DisplayName("Member can access KB A")
        void memberCanAccess() {
            KnowledgeBaseDetailTool tool = new KnowledgeBaseDetailTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(memberCtxA, new KnowledgeBaseDetailArguments(KB_A));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("KB A");
        }

        @Test
        @DisplayName("Outsider rejected from KB A")
        void outsiderRejected() {
            KnowledgeBaseDetailTool tool = new KnowledgeBaseDetailTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(outsiderCtx, new KnowledgeBaseDetailArguments(KB_A));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isNotNull();
        }

        @Test
        @DisplayName("Cross-workspace: ctx.workspace=A + KB B rejected")
        void crossWorkspaceKb() {
            KnowledgeBaseDetailTool tool = new KnowledgeBaseDetailTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ownerCtxA, new KnowledgeBaseDetailArguments(KB_B));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Non-existent KB ID rejected")
        void nonExistentKb() {
            KnowledgeBaseDetailTool tool = new KnowledgeBaseDetailTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ownerCtxA, new KnowledgeBaseDetailArguments(99999L));

            assertThat(result.success()).isFalse();
        }
    }

    // ================================================================
    // knowledge_base_list IDOR
    // ================================================================

    @Nested
    @DisplayName("knowledge_base_list - Real IDOR")
    class KnowledgeBaseListIdor {

        @Test
        @DisplayName("Owner can list KBs in WS A")
        void ownerCanList() {
            KnowledgeBaseListTool tool = new KnowledgeBaseListTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ownerCtxA, new KnowledgeBaseListArguments(1, 20));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("KB A");
            assertThat(result.content()).doesNotContain("KB B"); // KB B is in WS B
        }

        @Test
        @DisplayName("Member can list KBs in WS A")
        void memberCanList() {
            KnowledgeBaseListTool tool = new KnowledgeBaseListTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(memberCtxA, new KnowledgeBaseListArguments(1, 20));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("KB A");
        }

        @Test
        @DisplayName("Outsider rejected from listing KBs")
        void outsiderRejected() {
            KnowledgeBaseListTool tool = new KnowledgeBaseListTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(outsiderCtx, new KnowledgeBaseListArguments(1, 20));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Listing only returns current workspace KBs")
        void onlyReturnsCurrentWorkspaceKbs() {
            KnowledgeBaseListTool tool = new KnowledgeBaseListTool(knowledgeBaseService);
            AgentToolExecutionResult result = tool.execute(ownerCtxA, new KnowledgeBaseListArguments(1, 20));

            assertThat(result.success()).isTrue();
            // KB B should NOT appear because it's in WS B
            assertThat(result.content()).doesNotContain("KB B");
        }
    }

    // ================================================================
    // document_list IDOR
    // ================================================================

    @Nested
    @DisplayName("document_list - Real IDOR")
    class DocumentListIdor {

        @Test
        @DisplayName("Owner can list docs in KB A")
        void ownerCanList() {
            DocumentListTool tool = new DocumentListTool(documentService);
            AgentToolExecutionResult result = tool.execute(ownerCtxA, new DocumentListArguments(KB_A, 1, 20));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("doc-a.pdf");
        }

        @Test
        @DisplayName("Member can list docs in KB A")
        void memberCanList() {
            DocumentListTool tool = new DocumentListTool(documentService);
            AgentToolExecutionResult result = tool.execute(memberCtxA, new DocumentListArguments(KB_A, 1, 20));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("doc-a.pdf");
        }

        @Test
        @DisplayName("Outsider rejected from listing docs")
        void outsiderRejected() {
            DocumentListTool tool = new DocumentListTool(documentService);
            AgentToolExecutionResult result = tool.execute(outsiderCtx, new DocumentListArguments(KB_A, 1, 20));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Cross-workspace KB: ctx.workspace=A + KB B rejected")
        void crossWorkspaceKb() {
            DocumentListTool tool = new DocumentListTool(documentService);
            AgentToolExecutionResult result = tool.execute(ownerCtxA, new DocumentListArguments(KB_B, 1, 20));

            assertThat(result.success()).isFalse();
        }
    }

    // ================================================================
    // document_detail IDOR
    // ================================================================

    @Nested
    @DisplayName("document_detail - Real IDOR")
    class DocumentDetailIdor {

        @Test
        @DisplayName("Owner can access doc A in KB A")
        void ownerCanAccess() {
            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            AgentToolExecutionResult result = tool.execute(ownerCtxA, new DocumentDetailArguments(KB_A, DOC_A));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("doc-a.pdf");
        }

        @Test
        @DisplayName("Member can access doc A in KB A")
        void memberCanAccess() {
            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            AgentToolExecutionResult result = tool.execute(memberCtxA, new DocumentDetailArguments(KB_A, DOC_A));

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("doc-a.pdf");
        }

        @Test
        @DisplayName("Outsider rejected from doc A")
        void outsiderRejected() {
            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            AgentToolExecutionResult result = tool.execute(outsiderCtx, new DocumentDetailArguments(KB_A, DOC_A));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Cross-workspace: ctx.workspace=A + KB B + Doc B rejected")
        void crossWorkspaceDoc() {
            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            AgentToolExecutionResult result = tool.execute(ownerCtxA, new DocumentDetailArguments(KB_B, DOC_B));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Document/KB mismatch: ctx.workspace=A + KB A + Doc B rejected")
        void documentKbMismatch() {
            // Doc B belongs to KB B, not KB A
            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            AgentToolExecutionResult result = tool.execute(ownerCtxA, new DocumentDetailArguments(KB_A, DOC_B));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Non-existent document ID rejected")
        void nonExistentDoc() {
            DocumentDetailTool tool = new DocumentDetailTool(documentService);
            AgentToolExecutionResult result = tool.execute(ownerCtxA, new DocumentDetailArguments(KB_A, 99999L));

            assertThat(result.success()).isFalse();
        }
    }
}