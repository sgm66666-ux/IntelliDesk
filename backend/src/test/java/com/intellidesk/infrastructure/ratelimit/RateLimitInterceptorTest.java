package com.intellidesk.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.api_key.ApiKeyAuthenticationDetails;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitInterceptor Unit Tests")
class RateLimitInterceptorTest {

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private UserService userService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private RateLimitInterceptor interceptor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        objectMapper = new ObjectMapper();
        interceptor = new RateLimitInterceptor(rateLimitService, userService, objectMapper);
    }

    @Test
    @DisplayName("non-protected route: should no-op")
    void shouldNoOpForNonProtectedRoute() throws Exception {
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/knowledge-bases");
        when(request.getMethod()).thenReturn("GET");

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isTrue();
        verify(rateLimitService, never()).check(anyString(), anyString());
    }

    @Test
    @DisplayName("null pattern: should no-op")
    void shouldNoOpForNullPattern() throws Exception {
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn(null);

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isTrue();
        verify(rateLimitService, never()).check(anyString(), anyString());
    }

    @Test
    @DisplayName("JWT identity: uses user:{userId}")
    void shouldUseJwtUserId() throws Exception {
        User mockUser = new User();
        mockUser.setId(42L);
        mockUser.setUsername("testuser");

        when(userService.findByUsername("testuser")).thenReturn(mockUser);
        when(rateLimitService.check(eq("agent"), eq("user:42")))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/conversations/{conversationId}/agent/stream");
        when(request.getMethod()).thenReturn("POST");

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isTrue();
        verify(rateLimitService).check("agent", "user:42");
    }

    @Test
    @DisplayName("JWT: does NOT use username for quota key")
    void shouldNotUseUsernameForQuotaKey() throws Exception {
        User mockUser = new User();
        mockUser.setId(99L);
        mockUser.setUsername("differentuser");

        when(userService.findByUsername("differentuser")).thenReturn(mockUser);
        when(rateLimitService.check(eq("agent"), eq("user:99")))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("differentuser", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/conversations/{conversationId}/agent/stream");
        when(request.getMethod()).thenReturn("POST");

        interceptor.preHandle(request, response, null);

        verify(rateLimitService).check("agent", "user:99");
        verify(rateLimitService, never()).check(eq("agent"), eq("differentuser"));
    }

    @Test
    @DisplayName("API Key identity: uses apikey:{apiKeyId}")
    void shouldUseApiKeyId() throws Exception {
        ApiKeyAuthenticationDetails apiKeyDetails = new ApiKeyAuthenticationDetails(
                7L, 1L, "read", ApiKeyAuthenticationDetails.AUTH_TYPE_API_KEY);

        when(rateLimitService.check(eq("retrieval"), eq("apikey:7")))
                .thenReturn(RateLimitDecision.allowed(50, 1000, 60, 60));

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("apikey_user", null, List.of());
        auth.setDetails(apiKeyDetails);
        SecurityContextHolder.getContext().setAuthentication(auth);

        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/retrieval/search");
        when(request.getMethod()).thenReturn("POST");

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isTrue();
        verify(rateLimitService).check("retrieval", "apikey:7");
    }

    @Test
    @DisplayName("JWT user1 vs user2: isolated quotas")
    void shouldIsolateJwtUsers() throws Exception {
        User user1 = new User();
        user1.setId(1L);
        user1.setUsername("user1");
        User user2 = new User();
        user2.setId(2L);
        user2.setUsername("user2");

        when(userService.findByUsername("user1")).thenReturn(user1);
        when(userService.findByUsername("user2")).thenReturn(user2);
        when(rateLimitService.check(anyString(), anyString()))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user1", null, List.of()));
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/conversations/{conversationId}/agent/stream");
        when(request.getMethod()).thenReturn("POST");
        interceptor.preHandle(request, response, null);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user2", null, List.of()));
        interceptor.preHandle(request, response, null);

        verify(rateLimitService).check("agent", "user:1");
        verify(rateLimitService).check("agent", "user:2");
    }

    @Test
    @DisplayName("429 response: returns correct headers and body")
    void shouldReturn429WithCorrectHeaders() throws Exception {
        User mockUser = new User();
        mockUser.setId(42L);
        mockUser.setUsername("testuser");
        when(userService.findByUsername("testuser")).thenReturn(mockUser);

        RateLimitDecision rejected = RateLimitDecision.rejected(25, 1700000000, 10, 60);
        when(rateLimitService.check(eq("agent"), eq("user:42"))).thenReturn(rejected);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", null, List.of()));

        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/conversations/{conversationId}/agent/stream");
        when(request.getMethod()).thenReturn("POST");

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isFalse();
        verify(response).setStatus(429);
        verify(response).setHeader("Retry-After", "25");
        verify(response).setHeader("X-RateLimit-Limit", "10");
        verify(response).setHeader("X-RateLimit-Remaining", "0");
        verify(response).setHeader("X-RateLimit-Reset", "1700000000");
        verify(response).setContentType("application/json");

        printWriter.flush();
        String body = stringWriter.toString();
        assertThat(body).contains("8001");
        assertThat(body).contains("retryAfterSeconds");
        assertThat(body).contains("25");
    }

    @Test
    @DisplayName("429: controller not executed")
    void shouldNotExecuteControllerWhenRateLimited() throws Exception {
        User mockUser = new User();
        mockUser.setId(42L);
        mockUser.setUsername("testuser");
        when(userService.findByUsername("testuser")).thenReturn(mockUser);

        when(rateLimitService.check(eq("agent"), eq("user:42")))
                .thenReturn(RateLimitDecision.rejected(10, 1000, 10, 60));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", null, List.of()));

        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/conversations/{conversationId}/agent/stream");
        when(request.getMethod()).thenReturn("POST");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("category isolation: agent vs retrieval")
    void shouldIsolateAgentFromRetrieval() throws Exception {
        User mockUser = new User();
        mockUser.setId(42L);
        mockUser.setUsername("testuser");
        when(userService.findByUsername("testuser")).thenReturn(mockUser);
        when(rateLimitService.check(anyString(), anyString()))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", null, List.of()));

        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/conversations/{conversationId}/agent/stream");
        when(request.getMethod()).thenReturn("POST");
        interceptor.preHandle(request, response, null);

        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/retrieval/search");
        when(request.getMethod()).thenReturn("POST");
        interceptor.preHandle(request, response, null);

        verify(rateLimitService).check("agent", "user:42");
        verify(rateLimitService).check("retrieval", "user:42");
    }

    @Test
    @DisplayName("category isolation: chat vs upload")
    void shouldIsolateChatFromUpload() throws Exception {
        User mockUser = new User();
        mockUser.setId(42L);
        mockUser.setUsername("testuser");
        when(userService.findByUsername("testuser")).thenReturn(mockUser);
        when(rateLimitService.check(anyString(), anyString()))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", null, List.of()));

        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/conversations/{conversationId}/messages/stream");
        when(request.getMethod()).thenReturn("POST");
        interceptor.preHandle(request, response, null);

        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/knowledge-bases/{knowledgeBaseId}/documents");
        when(request.getMethod()).thenReturn("POST");
        interceptor.preHandle(request, response, null);

        verify(rateLimitService).check("chat", "user:42");
        verify(rateLimitService).check("upload", "user:42");
    }

    @Test
    @DisplayName("agent once per request: single SSE request counts 1 quota")
    void shouldCountAgentOncePerRequest() throws Exception {
        User mockUser = new User();
        mockUser.setId(42L);
        mockUser.setUsername("testuser");
        when(userService.findByUsername("testuser")).thenReturn(mockUser);
        when(rateLimitService.check(anyString(), anyString()))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", null, List.of()));

        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/conversations/{conversationId}/agent/stream");
        when(request.getMethod()).thenReturn("POST");

        interceptor.preHandle(request, response, null);

        // Agent SSE: only one rate limit check per HTTP request
        verify(rateLimitService, times(1)).check(anyString(), anyString());
    }

    @Test
    @DisplayName("chat once per request: single SSE request counts 1 quota")
    void shouldCountChatOncePerRequest() throws Exception {
        User mockUser = new User();
        mockUser.setId(42L);
        mockUser.setUsername("testuser");
        when(userService.findByUsername("testuser")).thenReturn(mockUser);
        when(rateLimitService.check(anyString(), anyString()))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", null, List.of()));

        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/conversations/{conversationId}/messages/stream");
        when(request.getMethod()).thenReturn("POST");

        interceptor.preHandle(request, response, null);

        // Chat SSE: only one rate limit check per HTTP request
        verify(rateLimitService, times(1)).check(anyString(), anyString());
    }

    @Test
    @DisplayName("BUSY: no rollback after admission")
    void shouldNotRollbackAfterAdmission() throws Exception {
        User mockUser = new User();
        mockUser.setId(42L);
        mockUser.setUsername("testuser");
        when(userService.findByUsername("testuser")).thenReturn(mockUser);
        when(rateLimitService.check(eq("agent"), eq("user:42")))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", null, List.of()));

        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/conversations/{conversationId}/agent/stream");
        when(request.getMethod()).thenReturn("POST");

        boolean result = interceptor.preHandle(request, response, null);

        // Admission succeeds, no rollback
        assertThat(result).isTrue();
        verify(rateLimitService).check("agent", "user:42");
        // No additional ZREM/ZADD compensation calls
        verify(rateLimitService, times(1)).check(anyString(), anyString());
    }

    @Test
    @DisplayName("invalid API Key: does not consume quota")
    void shouldNotConsumeQuotaForInvalidApiKey() throws Exception {
        // No authentication set - principal resolves to null
        SecurityContextHolder.clearContext();

        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/conversations/{conversationId}/agent/stream");
        when(request.getMethod()).thenReturn("POST");

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isTrue();
        verify(rateLimitService, never()).check(anyString(), anyString());
    }

    @Test
    @DisplayName("API Key key1 vs key2: isolated quotas")
    void shouldIsolateApiKeys() throws Exception {
        ApiKeyAuthenticationDetails key1 = new ApiKeyAuthenticationDetails(
                10L, 1L, "read", ApiKeyAuthenticationDetails.AUTH_TYPE_API_KEY);
        ApiKeyAuthenticationDetails key2 = new ApiKeyAuthenticationDetails(
                20L, 1L, "read", ApiKeyAuthenticationDetails.AUTH_TYPE_API_KEY);

        when(rateLimitService.check(anyString(), anyString()))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/retrieval/search");
        when(request.getMethod()).thenReturn("POST");

        UsernamePasswordAuthenticationToken auth1 = new UsernamePasswordAuthenticationToken("apikey_1", null, List.of());
        auth1.setDetails(key1);
        SecurityContextHolder.getContext().setAuthentication(auth1);
        interceptor.preHandle(request, response, null);

        UsernamePasswordAuthenticationToken auth2 = new UsernamePasswordAuthenticationToken("apikey_2", null, List.of());
        auth2.setDetails(key2);
        SecurityContextHolder.getContext().setAuthentication(auth2);
        interceptor.preHandle(request, response, null);

        verify(rateLimitService).check("retrieval", "apikey:10");
        verify(rateLimitService).check("retrieval", "apikey:20");
    }

    @Test
    @DisplayName("same owner JWT vs API Key: isolated quotas")
    void shouldIsolateJwtFromApiKeySameOwner() throws Exception {
        // JWT call for user 42
        User mockUser = new User();
        mockUser.setId(42L);
        mockUser.setUsername("owner");
        when(userService.findByUsername("owner")).thenReturn(mockUser);
        when(rateLimitService.check(anyString(), anyString()))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/retrieval/search");
        when(request.getMethod()).thenReturn("POST");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner", null, List.of()));
        interceptor.preHandle(request, response, null);

        // API Key call (same user 42, but different key)
        ApiKeyAuthenticationDetails apiKeyDetails = new ApiKeyAuthenticationDetails(
                5L, 1L, "read", ApiKeyAuthenticationDetails.AUTH_TYPE_API_KEY);
        UsernamePasswordAuthenticationToken auth2 = new UsernamePasswordAuthenticationToken("apikey_user", null, List.of());
        auth2.setDetails(apiKeyDetails);
        SecurityContextHolder.getContext().setAuthentication(auth2);
        interceptor.preHandle(request, response, null);

        verify(rateLimitService).check("retrieval", "user:42");
        verify(rateLimitService).check("retrieval", "apikey:5");
    }
}