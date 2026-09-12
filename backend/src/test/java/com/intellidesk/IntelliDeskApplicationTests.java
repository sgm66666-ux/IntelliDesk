package com.intellidesk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.auth.RefreshTokenService;
import com.intellidesk.auth.dto.LoginRequest;
import com.intellidesk.auth.dto.LoginResponse;
import com.intellidesk.auth.dto.RefreshTokenRequest;
import com.intellidesk.auth.dto.RegisterRequest;
import com.intellidesk.common.Result;
import com.intellidesk.workspace.Workspace;
import com.intellidesk.workspace.dto.AddMemberRequest;
import com.intellidesk.workspace.dto.WorkspaceCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
@DisplayName("Phase 1 Integration Tests")
class IntelliDeskApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    private final AtomicLong refreshTokenCounter = new AtomicLong(0);

    @BeforeEach
    void setUpMock() {
        when(refreshTokenService.createRefreshToken(anyLong()))
                .thenAnswer(invocation -> "mock-refresh-token-" + UUID.randomUUID());
    }

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

    private LoginRequest loginRequest(String username) {
        LoginRequest r = new LoginRequest();
        r.setUsername(username);
        r.setPassword("password123");
        return r;
    }

    private RefreshTokenRequest refreshTokenRequest(String token) {
        RefreshTokenRequest r = new RefreshTokenRequest();
        r.setRefreshToken(token);
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

    // ==================== Auth Tests ====================

    @Nested
    @DisplayName("Authentication")
    class AuthTests {

        @Test
        @DisplayName("should register user successfully")
        void shouldRegisterUser() throws Exception {
            String username = uniqueUsername();
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerRequest(username))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                    .andExpect(jsonPath("$.data.username").value(username));
        }

        @Test
        @DisplayName("should reject duplicate username registration")
        void shouldRejectDuplicateUsername() throws Exception {
            String username = uniqueUsername();
            registerAndGetTokens(username);
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerRequest(username))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(1002));
        }

        @Test
        @DisplayName("should login successfully")
        void shouldLoginSuccessfully() throws Exception {
            String username = uniqueUsername();
            registerAndGetTokens(username);
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest(username))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
        }

        @Test
        @DisplayName("should reject login with wrong password")
        void shouldRejectLoginWithWrongPassword() throws Exception {
            String username = uniqueUsername();
            registerAndGetTokens(username);
            LoginRequest req = new LoginRequest();
            req.setUsername(username);
            req.setPassword("wrongpassword");
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(1001));
        }

        @Test
        @DisplayName("should reject login with nonexistent user")
        void shouldRejectLoginWithNonexistentUser() throws Exception {
            LoginRequest req = new LoginRequest();
            req.setUsername("nonexistent_user");
            req.setPassword("password123");
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(1001));
        }

        @Test
        @DisplayName("should reject blank login credentials as bad request")
        void shouldRejectBlankLoginCredentials() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\" \",\"password\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("should reject malformed login JSON without leaking payload")
        void shouldRejectMalformedLoginJsonWithoutLeakingPayload() throws Exception {
            String malformedPayload = "{\"username\":\"attacker\",\"password\":\"secret-marker\"";

            MvcResult result = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformedPayload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("secret-marker")
                    .doesNotContain("JsonEOFException")
                    .doesNotContain("stackTrace");
        }

        @Test
        @DisplayName("should reject non-string login credentials without leaking payload")
        void shouldRejectNonStringLoginCredentialsWithoutLeakingPayload() throws Exception {
            String malformedPayload = "{\"username\":{\"secret\":\"type-marker\"},\"password\":\"password123\"}";

            MvcResult result = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformedPayload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("type-marker")
                    .doesNotContain("MismatchedInputException")
                    .doesNotContain("stackTrace");
        }

        @Test
        @DisplayName("should access protected endpoint with valid token")
        void shouldAccessProtectedEndpoint() throws Exception {
            String username = uniqueUsername();
            LoginResponse tokens = registerAndGetTokens(username);
            mockMvc.perform(get("/api/users/me")
                            .header("Authorization", "Bearer " + tokens.getAccessToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.username").value(username));
        }

        @Test
        @DisplayName("should reject access without token")
        void shouldRejectUnauthorizedAccess() throws Exception {
            mockMvc.perform(get("/api/users/me"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should reject access with invalid token")
        void shouldRejectInvalidToken() throws Exception {
            mockMvc.perform(get("/api/users/me")
                            .header("Authorization", "Bearer invalid_token_here"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should refresh token successfully")
        void shouldRefreshToken() throws Exception {
            String username = uniqueUsername();
            LoginResponse tokens = registerAndGetTokens(username);
            when(refreshTokenService.consumeRefreshToken(tokens.getRefreshToken()))
                    .thenReturn(tokens.getUserId());
            when(refreshTokenService.createRefreshToken(anyLong()))
                    .thenReturn("new-refresh-token-uuid");
            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(refreshTokenRequest(tokens.getRefreshToken()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.data.refreshToken").value("new-refresh-token-uuid"));
        }

        @Test
        @DisplayName("should reject refresh with invalid token")
        void shouldRejectRefreshWithInvalidToken() throws Exception {
            when(refreshTokenService.consumeRefreshToken("invalid-refresh-token"))
                    .thenReturn(null);
            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(refreshTokenRequest("invalid-refresh-token"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(1005));
        }

        @Test
        @DisplayName("should reject refresh after logout")
        void shouldRejectRefreshAfterLogout() throws Exception {
            String username = uniqueUsername();
            LoginResponse tokens = registerAndGetTokens(username);
            when(refreshTokenService.consumeRefreshToken(tokens.getRefreshToken()))
                    .thenReturn(tokens.getUserId());
            when(refreshTokenService.createRefreshToken(anyLong()))
                    .thenReturn("new-token");
            // First refresh succeeds
            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(refreshTokenRequest(tokens.getRefreshToken()))))
                    .andExpect(status().isOk());
            // Second refresh with same token fails (already consumed)
            when(refreshTokenService.consumeRefreshToken(tokens.getRefreshToken()))
                    .thenReturn(null);
            mockMvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(refreshTokenRequest(tokens.getRefreshToken()))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should logout successfully")
        void shouldLogout() throws Exception {
            String username = uniqueUsername();
            LoginResponse tokens = registerAndGetTokens(username);
            mockMvc.perform(post("/api/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + tokens.getRefreshToken() + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }
    }

    // ==================== Workspace Tests ====================

    @Nested
    @DisplayName("Workspace Management")
    class WorkspaceTests {

        private String username;
        private String accessToken;

        @BeforeEach
        void setUp() throws Exception {
            username = uniqueUsername();
            LoginResponse tokens = registerAndGetTokens(username);
            accessToken = tokens.getAccessToken();
        }

        private Long createWorkspaceAndGetId() throws Exception {
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

        @Test
        @DisplayName("should create workspace successfully")
        void shouldCreateWorkspace() throws Exception {
            WorkspaceCreateRequest req = new WorkspaceCreateRequest();
            req.setName("Test Workspace");
            req.setDescription("A test workspace");
            MvcResult result = mockMvc.perform(post("/api/workspaces")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.name").value("Test Workspace"))
                    .andReturn();
            String json = result.getResponse().getContentAsString();
            Result<Workspace> body = objectMapper.readValue(json, new TypeReference<>() {});
            assertThat(body.getData().getId()).isNotNull();
        }

        @Test
        @DisplayName("should list user workspaces")
        void shouldListWorkspaces() throws Exception {
            WorkspaceCreateRequest req = new WorkspaceCreateRequest();
            req.setName("My Workspace");
            req.setDescription("desc");
            mockMvc.perform(post("/api/workspaces")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/workspaces")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("should get workspace detail")
        void shouldGetWorkspaceDetail() throws Exception {
            Long wsId = createWorkspaceAndGetId();
            mockMvc.perform(get("/api/workspaces/" + wsId)
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.id").value(wsId));
        }

        @Test
        @DisplayName("should update workspace")
        void shouldUpdateWorkspace() throws Exception {
            Long wsId = createWorkspaceAndGetId();
            mockMvc.perform(put("/api/workspaces/" + wsId)
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Updated Workspace\",\"description\":\"Updated description\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.name").value("Updated Workspace"));
        }

        @Test
        @DisplayName("should delete workspace successfully")
        void shouldDeleteWorkspaceSuccessfully() throws Exception {
            Long wsId = createWorkspaceAndGetId();
            mockMvc.perform(delete("/api/workspaces/" + wsId)
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
            // Verify deleted
            mockMvc.perform(get("/api/workspaces/" + wsId)
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should get members list")
        void shouldGetMembers() throws Exception {
            Long wsId = createWorkspaceAndGetId();
            mockMvc.perform(get("/api/workspaces/" + wsId + "/members")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("should reject workspace access by non-member")
        void shouldRejectWorkspaceAccessByNonMember() throws Exception {
            Long wsId = createWorkspaceAndGetId();
            String userB = uniqueUsername();
            LoginResponse tokensB = registerAndGetTokens(userB);
            mockMvc.perform(get("/api/workspaces/" + wsId)
                            .header("Authorization", "Bearer " + tokensB.getAccessToken()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(2002));
        }

        @Test
        @DisplayName("should reject workspace update by non-owner")
        void shouldRejectWorkspaceUpdateByNonOwner() throws Exception {
            Long wsId = createWorkspaceAndGetId();
            String userB = uniqueUsername();
            LoginResponse tokensB = registerAndGetTokens(userB);
            mockMvc.perform(put("/api/workspaces/" + wsId)
                            .header("Authorization", "Bearer " + tokensB.getAccessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Hacked\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("should reject workspace delete by non-owner")
        void shouldRejectWorkspaceDeleteByNonOwner() throws Exception {
            Long wsId = createWorkspaceAndGetId();
            String userB = uniqueUsername();
            LoginResponse tokensB = registerAndGetTokens(userB);
            mockMvc.perform(delete("/api/workspaces/" + wsId)
                            .header("Authorization", "Bearer " + tokensB.getAccessToken()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("should reject add member to nonexistent workspace")
        void shouldRejectAddMemberToNonexistentWorkspace() throws Exception {
            AddMemberRequest req = new AddMemberRequest();
            req.setUserId(99999L);
            mockMvc.perform(post("/api/workspaces/99999/members")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should reject remove owner from workspace")
        void shouldRejectRemoveOwner() throws Exception {
            Long wsId = createWorkspaceAndGetId();
            MvcResult meResult = mockMvc.perform(get("/api/users/me")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andReturn();
            String json = meResult.getResponse().getContentAsString();
            Result<com.intellidesk.user.User> body = objectMapper.readValue(json, new TypeReference<>() {});
            Long ownerId = body.getData().getId();
            mockMvc.perform(delete("/api/workspaces/" + wsId + "/members/" + ownerId)
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(2005));
        }
    }
}
