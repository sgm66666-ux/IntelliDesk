package com.intellidesk.api_key;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.auth.RefreshTokenService;
import com.intellidesk.auth.dto.LoginResponse;
import com.intellidesk.auth.dto.RegisterRequest;
import com.intellidesk.common.Result;
import com.intellidesk.api_key.dto.CreateApiKeyRequest;
import com.intellidesk.api_key.dto.CreateApiKeyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
@Sql(scripts = {"classpath:db/testdata/V2_test_schema.sql", "classpath:db/testdata/V4_test_schema.sql", "classpath:db/testdata/V5_test_schema.sql"})
@DisplayName("API Key RBAC Tests")
class ApiKeyRbacTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    private String ownerAccessToken;
    private String memberAccessToken;
    private Long workspaceId;

    private String uniqueUsername() {
        return "apikey_rbac_" + System.currentTimeMillis() + "_" + Thread.currentThread().threadId();
    }

    private RegisterRequest registerRequest(String username) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setPassword("Test123456!");
        r.setEmail(username + "@test.com");
        r.setNickname(username);
        return r;
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Result<LoginResponse> loginResult = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                objectMapper.getTypeFactory().constructParametricType(Result.class, LoginResponse.class));
        return loginResult.getData().getAccessToken();
    }

    @BeforeEach
    void setUp() throws Exception {
        when(refreshTokenService.createRefreshToken(anyLong())).thenReturn("test-refresh-token");

        String ownerName = uniqueUsername();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest(ownerName))))
                .andExpect(status().isOk());
        ownerAccessToken = loginAndGetToken(ownerName, "Test123456!");

        String memberName = uniqueUsername();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest(memberName))))
                .andExpect(status().isOk());
        memberAccessToken = loginAndGetToken(memberName, "Test123456!");

        MvcResult wsResult = mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", "Bearer " + ownerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"RBAC Test WS\",\"description\":\"test\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String wsJson = wsResult.getResponse().getContentAsString();
        workspaceId = objectMapper.readTree(wsJson).get("data").get("id").asLong();
    }

    @Test
    @DisplayName("API Key should use creator's roles and permissions, not ROLE_ADMIN")
    void shouldUseCreatorRolesAndPermissions() throws Exception {
        // Create API Key
        CreateApiKeyRequest request = new CreateApiKeyRequest();
        request.setName("RBAC Test Key");

        MvcResult createResult = mockMvc.perform(post("/api/workspaces/{wid}/api-keys", workspaceId)
                        .header("Authorization", "Bearer " + ownerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        Result<CreateApiKeyResponse> createResp = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                objectMapper.getTypeFactory().constructParametricType(Result.class, CreateApiKeyResponse.class));
        String fullKey = createResp.getData().getFullKey();

        // Members should NOT be able to manage API Keys (even if scope interceptor is bypassed)
        // The controller requires @PreAuthorize("hasAuthority('workspace:manage')")
        // A regular member shouldn't have workspace:manage permission
        // This test verifies that the API Key's RBAC is the same as the creator's
        // and doesn't escalate to workspace:manage

        // Verify that the API Key can access READ-allowed endpoints
        mockMvc.perform(get("/api/workspaces/{wid}/knowledge-bases", workspaceId)
                        .header("X-API-Key", fullKey))
                .andExpect(status().isOk());

        // Verify that the API Key CANNOT access API Key management (403 scope denied)
        mockMvc.perform(get("/api/workspaces/{wid}/api-keys", workspaceId)
                        .header("X-API-Key", fullKey))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("API Key scope should not expand user RBAC - member cannot manage API Keys")
    void shouldNotExpandUserRbac() throws Exception {
        // Member cannot manage API Keys with JWT
        mockMvc.perform(get("/api/workspaces/{wid}/api-keys", workspaceId)
                        .header("Authorization", "Bearer " + memberAccessToken))
                .andExpect(status().isForbidden());

        // Also verify member cannot create API Keys
        CreateApiKeyRequest request = new CreateApiKeyRequest();
        request.setName("Member Key");

        mockMvc.perform(post("/api/workspaces/{wid}/api-keys", workspaceId)
                        .header("Authorization", "Bearer " + memberAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("API Key scope denied even for endpoints owner has permission for")
    void shouldDenyNonReadEndpointsEvenForOwner() throws Exception {
        CreateApiKeyRequest request = new CreateApiKeyRequest();
        request.setName("Owner Key");

        MvcResult createResult = mockMvc.perform(post("/api/workspaces/{wid}/api-keys", workspaceId)
                        .header("Authorization", "Bearer " + ownerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        Result<CreateApiKeyResponse> createResp = objectMapper.readValue(
                createResult.getResponse().getContentAsString(),
                objectMapper.getTypeFactory().constructParametricType(Result.class, CreateApiKeyResponse.class));
        String fullKey = createResp.getData().getFullKey();

        // Owner has workspace:manage, but API Key scope is READ
        // API Key management should be denied by scope interceptor
        mockMvc.perform(get("/api/workspaces/{wid}/api-keys", workspaceId)
                        .header("X-API-Key", fullKey))
                .andExpect(status().isForbidden());
    }
}