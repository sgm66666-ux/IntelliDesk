package com.intellidesk.retrieval.indexing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.auth.RefreshTokenService;
import com.intellidesk.auth.dto.LoginRequest;
import com.intellidesk.auth.dto.LoginResponse;
import com.intellidesk.auth.dto.RegisterRequest;
import com.intellidesk.common.Result;
import com.intellidesk.retrieval.task.DocumentRetrievalTask;
import com.intellidesk.workspace.Workspace;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
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

import java.time.LocalDateTime;
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
@DisplayName("DocumentRetrievalController Reindex Tests")
class DocumentRetrievalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @MockitoBean
    private RetrievalTaskService retrievalTaskService;

    private String ownerAccessToken;
    private String memberAccessToken;
    private String outsiderAccessToken;
    private Long workspaceId;
    private Long ownerUserId;
    private Long memberUserId;

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

        // Owner creates workspace and adds member
        workspaceId = createWorkspaceAndGetId(ownerAccessToken);

        // Add member to workspace
        mockMvc.perform(post("/api/workspaces/" + workspaceId + "/members")
                        .header("Authorization", "Bearer " + ownerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + memberUserId + "}"))
                .andExpect(status().isOk());

        // Setup mock reindex result
        DocumentRetrievalTask task = new DocumentRetrievalTask();
        task.setId(1L);
        task.setDocumentId(1L);
        task.setGeneration(2);
        task.setStatus("READY");
        task.setCreatedAt(LocalDateTime.now());
        when(retrievalTaskService.reindex(anyLong(), anyLong())).thenReturn(task);
    }

    // ================================================================
    // Reindex API Tests
    // ================================================================

    @Nested
    @DisplayName("Reindex - Auth")
    class ReindexAuth {

        @Test
        @DisplayName("Owner can reindex")
        void ownerCanReindex() throws Exception {
            mockMvc.perform(post("/api/workspaces/" + workspaceId + "/knowledge-bases/1/documents/1/retrieval/reindex")
                            .header("Authorization", "Bearer " + ownerAccessToken))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.documentId").value(1))
                    .andExpect(jsonPath("$.data.generation").value(2));
        }

        @Test
        @DisplayName("Member cannot reindex")
        void memberCannotReindex() throws Exception {
            mockMvc.perform(post("/api/workspaces/" + workspaceId + "/knowledge-bases/1/documents/1/retrieval/reindex")
                            .header("Authorization", "Bearer " + memberAccessToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Outsider gets 403")
        void outsiderBlocked() throws Exception {
            mockMvc.perform(post("/api/workspaces/" + workspaceId + "/knowledge-bases/1/documents/1/retrieval/reindex")
                            .header("Authorization", "Bearer " + outsiderAccessToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("No token gets 401")
        void noTokenBlocked() throws Exception {
            mockMvc.perform(post("/api/workspaces/" + workspaceId + "/knowledge-bases/1/documents/1/retrieval/reindex"))
                    .andExpect(status().isUnauthorized());
        }
    }
}