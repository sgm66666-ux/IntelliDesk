package com.intellidesk.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.auth.RefreshTokenService;
import com.intellidesk.auth.dto.LoginRequest;
import com.intellidesk.auth.dto.LoginResponse;
import com.intellidesk.auth.dto.RegisterRequest;
import com.intellidesk.common.Result;
import com.intellidesk.retrieval.dto.RetrievalSearchRequest;
import com.intellidesk.workspace.Workspace;
import com.intellidesk.workspace.dto.WorkspaceCreateRequest;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
@DisplayName("RetrievalController API Tests")
class RetrievalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @MockitoBean
    private RetrievalService retrievalService;

    @MockitoBean
    private RetrievalScopeResolver scopeResolver;

    private String ownerAccessToken;
    private String memberAccessToken;
    private String outsiderAccessToken;
    private Long workspaceId;
    private Long ownerUserId;
    private Long memberUserId;
    private Long outsiderUserId;

    private String uniqueUsername() {
        return "testuser_" + System.currentTimeMillis() + "_" + Thread.currentThread().threadId();
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

    private Long createWorkspaceAndGetId(String accessToken) throws Exception {
        WorkspaceCreateRequest req = new WorkspaceCreateRequest();
        req.setName("Test WS " + System.currentTimeMillis());
        req.setDescription("desc");
        MvcResult result = mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        Result<Workspace> body = objectMapper.readValue(json, new TypeReference<>() {});
        return body.getData().getId();
    }

    @BeforeEach
    void setUp() throws Exception {
        when(refreshTokenService.createRefreshToken(anyLong()))
                .thenAnswer(inv -> "mock-refresh-token-" + UUID.randomUUID());

        // Create 3 users: owner, member, outsider
        LoginResponse ownerTokens = registerAndGetTokens(uniqueUsername());
        ownerAccessToken = ownerTokens.getAccessToken();
        ownerUserId = ownerTokens.getUserId();

        LoginResponse memberTokens = registerAndGetTokens(uniqueUsername());
        memberAccessToken = memberTokens.getAccessToken();
        memberUserId = memberTokens.getUserId();

        LoginResponse outsiderTokens = registerAndGetTokens(uniqueUsername());
        outsiderAccessToken = outsiderTokens.getAccessToken();
        outsiderUserId = outsiderTokens.getUserId();

        // Owner creates workspace and adds member
        workspaceId = createWorkspaceAndGetId(ownerAccessToken);

        // Add member to workspace
        mockMvc.perform(post("/api/workspaces/" + workspaceId + "/members")
                        .header("Authorization", "Bearer " + ownerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + memberUserId + "}"))
                .andExpect(status().isOk());

        // Setup mock scope resolver
        RetrievalScope scope = RetrievalScope.of(workspaceId, List.of(1L));
        when(scopeResolver.resolve(eq(workspaceId), anyList(), any(), anyLong()))
                .thenReturn(scope);

        // Setup mock retrieval service
        RetrievalResult result = new RetrievalResult(1L, 1L, 1L, "test content",
                0.95f, ScoreType.COSINE_SIMILARITY, 0, "section1");
        when(retrievalService.search(any(), any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(result));
    }

    // ================================================================
    // Search API Tests
    // ================================================================

    @Nested
    @DisplayName("Search API - Auth")
    class SearchAuth {

        @Test
        @DisplayName("Owner can search")
        void ownerCanSearch() throws Exception {
            RetrievalSearchRequest req = new RetrievalSearchRequest();
            req.setQuery("test query");
            req.setKnowledgeBaseIds(List.of(1L));

            mockMvc.perform(post("/api/workspaces/" + workspaceId + "/retrieval/search")
                            .header("Authorization", "Bearer " + ownerAccessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }

        @Test
        @DisplayName("Member can search")
        void memberCanSearch() throws Exception {
            RetrievalSearchRequest req = new RetrievalSearchRequest();
            req.setQuery("test query");
            req.setKnowledgeBaseIds(List.of(1L));

            mockMvc.perform(post("/api/workspaces/" + workspaceId + "/retrieval/search")
                            .header("Authorization", "Bearer " + memberAccessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }

        @Test
        @DisplayName("Outsider gets 403")
        void outsiderBlocked() throws Exception {
            doThrow(new com.intellidesk.common.BusinessException(
                    com.intellidesk.common.ErrorCode.WORKSPACE_ACCESS_DENIED))
                    .when(scopeResolver).resolve(eq(workspaceId), anyList(), any(), anyLong());

            RetrievalSearchRequest req = new RetrievalSearchRequest();
            req.setQuery("test query");
            req.setKnowledgeBaseIds(List.of(1L));

            mockMvc.perform(post("/api/workspaces/" + workspaceId + "/retrieval/search")
                            .header("Authorization", "Bearer " + outsiderAccessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("No token gets 401")
        void noTokenBlocked() throws Exception {
            RetrievalSearchRequest req = new RetrievalSearchRequest();
            req.setQuery("test query");
            req.setKnowledgeBaseIds(List.of(1L));

            mockMvc.perform(post("/api/workspaces/" + workspaceId + "/retrieval/search")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Search API - IDOR")
    class SearchIDOR {

        @Test
        @DisplayName("Cross-workspace KB returns 404")
        void crossWorkspaceKB() throws Exception {
            doThrow(new com.intellidesk.common.BusinessException(
                    com.intellidesk.common.ErrorCode.KNOWLEDGE_BASE_NOT_FOUND))
                    .when(scopeResolver).resolve(eq(workspaceId), anyList(), any(), anyLong());

            RetrievalSearchRequest req = new RetrievalSearchRequest();
            req.setQuery("test query");
            req.setKnowledgeBaseIds(List.of(999L));

            mockMvc.perform(post("/api/workspaces/" + workspaceId + "/retrieval/search")
                            .header("Authorization", "Bearer " + ownerAccessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Cross-workspace document returns 404")
        void crossWorkspaceDocument() throws Exception {
            doThrow(new com.intellidesk.common.BusinessException(
                    com.intellidesk.common.ErrorCode.DOCUMENT_NOT_FOUND))
                    .when(scopeResolver).resolve(eq(workspaceId), anyList(), any(), anyLong());

            RetrievalSearchRequest req = new RetrievalSearchRequest();
            req.setQuery("test query");
            req.setKnowledgeBaseIds(List.of(1L));
            req.setDocumentIds(List.of(999L));

            mockMvc.perform(post("/api/workspaces/" + workspaceId + "/retrieval/search")
                            .header("Authorization", "Bearer " + ownerAccessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Search API - Validation")
    class SearchValidation {

        @Test
        @DisplayName("empty query returns 400")
        void emptyQuery() throws Exception {
            RetrievalSearchRequest req = new RetrievalSearchRequest();
            req.setQuery("");
            req.setKnowledgeBaseIds(List.of(1L));

            mockMvc.perform(post("/api/workspaces/" + workspaceId + "/retrieval/search")
                            .header("Authorization", "Bearer " + ownerAccessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("null KB IDs returns 400")
        void nullKbIds() throws Exception {
            RetrievalSearchRequest req = new RetrievalSearchRequest();
            req.setQuery("test query");

            mockMvc.perform(post("/api/workspaces/" + workspaceId + "/retrieval/search")
                            .header("Authorization", "Bearer " + ownerAccessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }
    }
}