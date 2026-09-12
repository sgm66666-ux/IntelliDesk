package com.intellidesk.auth;

import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.infrastructure.config.RateLimitProperties;
import com.intellidesk.infrastructure.ratelimit.RateLimitDecision;
import com.intellidesk.infrastructure.ratelimit.RateLimitService;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestInfrastructureConfig.class)
@DisplayName("Auth Cookie Tests - HttpOnly Refresh Token Cookie Contract")
class AuthCookieTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private AuthCookieProperties authCookieProperties;

    private final AtomicLong tokenCounter = new AtomicLong(0);
    private final Set<String> consumedTokens = new HashSet<>();

    @BeforeEach
    void setUpMocks() {
        consumedTokens.clear();
        tokenCounter.set(0);

        when(refreshTokenService.createRefreshToken(anyLong()))
                .thenAnswer(inv -> "mock-token-" + tokenCounter.incrementAndGet());

        when(refreshTokenService.consumeRefreshToken(anyString()))
                .thenAnswer(inv -> {
                    String token = inv.getArgument(0);
                    if (token != null && token.startsWith("mock-token-") && !consumedTokens.contains(token)) {
                        consumedTokens.add(token);
                        return 1L;
                    }
                    return null;
                });
        doAnswer(inv -> {
            consumedTokens.add(inv.getArgument(0));
            return null;
        }).when(refreshTokenService).revokeRefreshToken(anyString());

        when(rateLimitService.check(anyString(), anyString()))
                .thenReturn(RateLimitDecision.allowed());
    }

    private String uniqueUsername() {
        return "cookieuser_" + System.currentTimeMillis() + "_" + Thread.currentThread().threadId();
    }

    private Cookie extractCookie(MvcResult result, String name) {
        return result.getResponse().getCookie(name);
    }

    private Cookie registerAndGetCookie(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"testPassword123\",\"email\":\"" + username + "@test.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return extractCookie(result, "refresh_token");
    }

    // ==================== Test 1: login sets refresh_token cookie ====================

    @Test
    @DisplayName("login sets refresh_token cookie")
    void loginSetsRefreshCookie() throws Exception {
        String username = uniqueUsername();
        registerAndGetCookie(username);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"testPassword123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        Cookie cookie = extractCookie(result, "refresh_token");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotEmpty();
    }

    @Test
    @DisplayName("production-style cookie configuration is secure for set and clear")
    void productionStyleCookieConfigurationIsSecureForSetAndClear() {
        AuthCookieProperties productionProperties = new AuthCookieProperties();
        productionProperties.setSecure(true);
        CookieUtil productionCookieUtil = new CookieUtil(productionProperties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        productionCookieUtil.setRefreshTokenCookie(response, "refresh-token");
        productionCookieUtil.clearRefreshTokenCookie(response);

        Cookie setCookie = response.getCookies()[0];
        Cookie clearCookie = response.getCookies()[1];
        assertThat(setCookie.getSecure()).isTrue();
        assertThat(clearCookie.getSecure()).isTrue();
        assertThat(setCookie.isHttpOnly()).isTrue();
        assertThat(clearCookie.isHttpOnly()).isTrue();
        assertThat(setCookie.getPath()).isEqualTo("/api/auth");
        assertThat(clearCookie.getPath()).isEqualTo("/api/auth");
        assertThat(setCookie.getMaxAge()).isEqualTo(604800);
        assertThat(clearCookie.getMaxAge()).isZero();
        assertThat(setCookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(clearCookie.getAttribute("SameSite")).isEqualTo("Lax");
    }

    // ==================== Test 2: register sets refresh_token cookie ====================

    @Test
    @DisplayName("register sets refresh_token cookie")
    void registerSetsRefreshCookie() throws Exception {
        String username = uniqueUsername();

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"testPassword123\",\"email\":\"" + username + "@test.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        Cookie cookie = extractCookie(result, "refresh_token");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotEmpty();
    }

    // ==================== Test 3: cookie is HttpOnly ====================

    @Test
    @DisplayName("refresh_token cookie is HttpOnly")
    void cookieIsHttpOnly() throws Exception {
        String username = uniqueUsername();
        Cookie cookie = registerAndGetCookie(username);

        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
    }

    // ==================== Test 4: cookie has SameSite=Lax ====================

    @Test
    @DisplayName("refresh_token cookie has SameSite=Lax")
    void cookieHasSameSiteLax() throws Exception {
        String username = uniqueUsername();
        Cookie cookie = registerAndGetCookie(username);

        assertThat(cookie).isNotNull();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    }

    // ==================== Test 5: cookie has Path=/api/auth ====================

    @Test
    @DisplayName("refresh_token cookie has Path=/api/auth")
    void cookieHasCorrectPath() throws Exception {
        String username = uniqueUsername();
        Cookie cookie = registerAndGetCookie(username);

        assertThat(cookie).isNotNull();
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
    }

    // ==================== Test 6: cookie has Max-Age=604800 ====================

    @Test
    @DisplayName("refresh_token cookie has Max-Age=604800")
    void cookieHasCorrectMaxAge() throws Exception {
        String username = uniqueUsername();
        Cookie cookie = registerAndGetCookie(username);

        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge()).isEqualTo(604800);
    }

    // ==================== Test 7: refresh using cookie succeeds ====================

    @Test
    @DisplayName("refresh using cookie succeeds (no body token needed)")
    void refreshUsingCookieSucceeds() throws Exception {
        String username = uniqueUsername();
        Cookie cookie = registerAndGetCookie(username);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"dummy-body-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty());
    }

    @Test
    @DisplayName("refresh using cookie accepts an empty request body")
    void refreshUsingCookieAcceptsEmptyRequestBody() throws Exception {
        String username = uniqueUsername();
        Cookie cookie = registerAndGetCookie(username);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("refresh rotation sets new cookie")
    void refreshRotationSetsNewCookie() throws Exception {
        String username = uniqueUsername();
        Cookie oldCookie = registerAndGetCookie(username);

        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(oldCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"dummy-body-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        Cookie newCookie = extractCookie(refreshResult, "refresh_token");
        assertThat(newCookie).isNotNull();
        assertThat(newCookie.getValue()).isNotEmpty();
        assertThat(newCookie.getValue()).isNotEqualTo(oldCookie.getValue());
    }

    // ==================== Test 9: old refresh token cannot be reused ====================

    @Test
    @DisplayName("old refresh token cannot be reused after rotation")
    void oldTokenCannotBeReusedAfterRotation() throws Exception {
        String username = uniqueUsername();
        Cookie oldCookie = registerAndGetCookie(username);

        // First refresh consumes the old token
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(oldCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"dummy-body-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // Second refresh with the same old token should fail
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(oldCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"dummy-body-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1005));
    }

    // ==================== Test 10: logout using cookie succeeds ====================

    @Test
    @DisplayName("logout using cookie succeeds")
    void logoutUsingCookieSucceeds() throws Exception {
        String username = uniqueUsername();
        Cookie cookie = registerAndGetCookie(username);

        mockMvc.perform(post("/api/auth/logout")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ==================== Test 11: logout clears cookie ====================

    @Test
    @DisplayName("logout with cookie and empty body clears refresh token cookie")
    void logoutWithCookieAndEmptyBodyClearsRefreshTokenCookie() throws Exception {
        String username = uniqueUsername();
        Cookie cookie = registerAndGetCookie(username);

        mockMvc.perform(post("/api/auth/logout")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(cookie().maxAge("refresh_token", 0))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andExpect(cookie().secure("refresh_token", false))
                .andExpect(cookie().path("refresh_token", "/api/auth"));
    }

    @Test
    @DisplayName("logout with cookie accepts no request body and revokes the token")
    void logoutWithCookieAndNoBodyRevokesToken() throws Exception {
        String username = uniqueUsername();
        Cookie cookie = registerAndGetCookie(username);

        mockMvc.perform(post("/api/auth/logout").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(cookie().maxAge("refresh_token", 0))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andExpect(cookie().secure("refresh_token", false))
                .andExpect(cookie().path("refresh_token", "/api/auth"));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== Test 12: legacy body refresh still works ====================

    @Test
    @DisplayName("legacy body refresh still works")
    void legacyBodyRefreshStillWorks() throws Exception {
        String username = uniqueUsername();
        Cookie cookie = registerAndGetCookie(username);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + cookie.getValue() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    // ==================== Test 13: legacy body logout still works ====================

    @Test
    @DisplayName("legacy body logout still works")
    void legacyBodyLogoutStillWorks() throws Exception {
        String username = uniqueUsername();
        Cookie cookie = registerAndGetCookie(username);

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + cookie.getValue() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ==================== Test 14: invalid refresh token returns 401 ====================

    @Test
    @DisplayName("invalid refresh token returns 401")
    void invalidRefreshTokenReturns401() throws Exception {
        Cookie invalidCookie = new Cookie("refresh_token", "invalid-token-value");

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(invalidCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"dummy-body-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1005));
    }

    // ==================== Test 15: cookie flow does not bypass rate limit ====================

    @Test
    @DisplayName("cookie flow does not bypass rate limit")
    void cookieFlowDoesNotBypassRateLimit() throws Exception {
        String username = uniqueUsername();

        // Enable rate limiting for this test
        rateLimitProperties.setEnabled(true);
        rateLimitProperties.setWindowSeconds(60);
        rateLimitProperties.setAuth(1);

        try {
            // First login: rate limit allows
            when(rateLimitService.check(eq("auth"), anyString()))
                    .thenReturn(RateLimitDecision.allowed());

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"testPassword123\",\"email\":\"" + username + "@test.com\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));

            // Second request: rate limit denies
            when(rateLimitService.check(eq("auth"), anyString()))
                    .thenReturn(RateLimitDecision.rejected(30, System.currentTimeMillis() / 1000 + 60, 1, 60));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"testPassword123\"}"))
                    .andExpect(status().is(429));
        } finally {
            rateLimitProperties.setEnabled(false);
        }
    }
}