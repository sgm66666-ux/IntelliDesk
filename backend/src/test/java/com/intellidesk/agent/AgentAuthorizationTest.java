package com.intellidesk.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.auth.RefreshTokenService;
import com.intellidesk.auth.dto.LoginResponse;
import com.intellidesk.auth.dto.RegisterRequest;
import com.intellidesk.chat.conversation.Conversation;
import com.intellidesk.chat.dto.ConversationCreateRequest;
import com.intellidesk.chat.dto.AgentChatRequest;
import com.intellidesk.common.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent endpoint authorization and IDOR tests.
 * <p>
 * Verifies:
 * <ul>
 *   <li>Owner can access agent endpoint</li>
 *   <li>Workspace member (not conversation owner) gets scoped not-found</li>
 *   <li>Outsider gets forbidden</li>
 *   <li>Cross-workspace conversation is not accessible</li>
 *   <li>Unauthenticated gets 401</li>
 * </ul>
 * <p>
 * Does NOT modify Phase 4 authorization system. Tests existing behavior only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
@Sql(scripts = "classpath:db/testdata/V4_test_schema.sql")
@DisplayName("Agent Authorization & IDOR Tests")
class AgentAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    private String ownerAccessToken;
    private String memberAccessToken;
    private String outsiderAccessToken;
    private Long workspaceId;
    private Long otherWorkspaceId;
    private Long conversationId;
    private Long crossWorkspaceConversationId;

    private String uniqueUsername() {
        return "agent_test_" + System.currentTimeMillis() + "_" + Thread.currentThread().threadId();
    }

    private RegisterRequest registerRequest(String username) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setPassword("password123");
        r.setEmail(username + "@example.com");
        r.setNickname("Test User");
        return r;
    }

    private LoginResponse registerAndGetTokens(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest(username))))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        Result<LoginResponse> body = objectMapper.readValue(json, new TypeReference<>() {});
        return body.getData();
    }

    private Long createWorkspace(String accessToken) throws Exception {
        com.intellidesk.workspace.dto.WorkspaceCreateRequest req =
                new com.intellidesk.workspace.dto.WorkspaceCreateRequest();
        req.setName("Agent Test WS " + System.currentTimeMillis());
        req.setDescription("desc");
        MvcResult result = mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        Result<com.intellidesk.workspace.Workspace> body =
                objectMapper.readValue(json, new TypeReference<>() {});
        return body.getData().getId();
    }

    private Long createConversation(String accessToken, Long wsId) throws Exception {
        ConversationCreateRequest req = new ConversationCreateRequest();
        req.setTitle("Agent Test Conversation");
        MvcResult result = mockMvc.perform(post("/api/workspaces/" + wsId + "/conversations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        Result<Conversation> body = objectMapper.readValue(json, new TypeReference<>() {});
        return body.getData().getId();
    }

    @BeforeEach
    void setUp() throws Exception {
        when(refreshTokenService.createRefreshToken(anyLong()))
                .thenAnswer(inv -> "mock-refresh-token-" + UUID.randomUUID());

        // Register 3 users: owner, member, outsider
        LoginResponse ownerTokens = registerAndGetTokens(uniqueUsername());
        ownerAccessToken = ownerTokens.getAccessToken();

        LoginResponse memberTokens = registerAndGetTokens(uniqueUsername());
        memberAccessToken = memberTokens.getAccessToken();

        LoginResponse outsiderTokens = registerAndGetTokens(uniqueUsername());
        outsiderAccessToken = outsiderTokens.getAccessToken();

        // Owner creates workspace
        workspaceId = createWorkspace(ownerAccessToken);

        // Add member to workspace
        mockMvc.perform(post("/api/workspaces/" + workspaceId + "/members")
                        .header("Authorization", "Bearer " + ownerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + memberTokens.getUserId() + "}"))
                .andExpect(status().isOk());

        // Owner creates conversation in workspace
        conversationId = createConversation(ownerAccessToken, workspaceId);

        // Owner creates another workspace for cross-workspace test
        otherWorkspaceId = createWorkspace(ownerAccessToken);

        // Owner creates conversation in the other workspace
        crossWorkspaceConversationId = createConversation(ownerAccessToken, otherWorkspaceId);
    }

    private AgentChatRequest agentRequest(String query) {
        AgentChatRequest req = new AgentChatRequest();
        req.setQuery(query);
        return req;
    }

    private MvcResult performAgentRequest(String accessToken, Long wsId, Long convId) throws Exception {
        var requestBuilder = post("/api/workspaces/" + wsId + "/conversations/" + convId + "/agent/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(agentRequest("test query")));

        if (accessToken != null) {
            requestBuilder.header("Authorization", "Bearer " + accessToken);
        }

        return mockMvc.perform(requestBuilder).andReturn();
    }

    // ================================================================
    // Auth Tests
    // ================================================================

    @Nested
    @DisplayName("Agent Endpoint - Auth")
    class AgentAuthTests {

        @Test
        @DisplayName("Owner can access agent endpoint")
        void ownerCanAccess() throws Exception {
            MvcResult result = performAgentRequest(ownerAccessToken, workspaceId, conversationId);

            // Owner should be allowed — SSE connection established
            // The LLM mock may cause an error, but the endpoint itself is accessible
            assert result.getResponse().getStatus() == 200
                    : "Expected 200, got " + result.getResponse().getStatus();
        }

        @Test
        @DisplayName("Workspace member (not conversation owner) gets 404")
        void memberNotConversationOwnerGets404() throws Exception {
            MvcResult result = performAgentRequest(memberAccessToken, workspaceId, conversationId);

            // Member is workspace member but not conversation owner
            // verifyAccess checks user ownership → CHAT_CONVERSATION_NOT_FOUND
            // BusinessException is thrown synchronously before SSE stream starts → JSON error response
            int status = result.getResponse().getStatus();
            String content = result.getResponse().getContentAsString();
            assert status == 404 || content.contains("6001")
                    : "Expected 404 or CHAT_CONVERSATION_NOT_FOUND, got status=" + status + ", body=" + content;
        }

        @Test
        @DisplayName("Outsider gets forbidden (403)")
        void outsiderGetsForbidden() throws Exception {
            MvcResult result = performAgentRequest(outsiderAccessToken, workspaceId, conversationId);

            // Outsider is not workspace member
            // verifyAccess → workspaceAuth.requireMember → WORKSPACE_ACCESS_DENIED
            // BusinessException is thrown synchronously before SSE stream starts → JSON error response
            int status = result.getResponse().getStatus();
            String content = result.getResponse().getContentAsString();
            assert status == 403 || content.contains("2002")
                    : "Expected 403 or WORKSPACE_ACCESS_DENIED, got status=" + status + ", body=" + content;
        }

        @Test
        @DisplayName("Cross-workspace conversation returns 404")
        void crossWorkspaceConversationNotFound() throws Exception {
            // Access conversation from otherWorkspaceId via workspaceId endpoint
            MvcResult result = performAgentRequest(ownerAccessToken, workspaceId, crossWorkspaceConversationId);

            // Conversation belongs to otherWorkspaceId, not workspaceId
            // verifyAccess checks workspace match → CHAT_CONVERSATION_NOT_FOUND
            // BusinessException is thrown synchronously before SSE stream starts → JSON error response
            int status = result.getResponse().getStatus();
            String content = result.getResponse().getContentAsString();
            assert status == 404 || content.contains("6001")
                    : "Expected 404 or CHAT_CONVERSATION_NOT_FOUND, got status=" + status + ", body=" + content;
        }

        @Test
        @DisplayName("Unauthenticated gets 401")
        void unauthenticatedGets401() throws Exception {
            MvcResult result = performAgentRequest(null, workspaceId, conversationId);

            assert result.getResponse().getStatus() == 401
                    : "Expected 401, got " + result.getResponse().getStatus();
        }
    }
}