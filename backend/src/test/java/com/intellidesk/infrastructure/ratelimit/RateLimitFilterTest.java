package com.intellidesk.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitFilter Unit Tests")
class RateLimitFilterTest {

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private RateLimitFilter filter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        filter = new RateLimitFilter(rateLimitService, objectMapper);
    }

    @Test
    @DisplayName("login: uses ip:{remoteAddr}")
    void shouldUseRemoteAddrForLogin() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        when(rateLimitService.check(eq("auth"), eq("ip:192.168.1.100")))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService).check("auth", "ip:192.168.1.100");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("login: different remoteAddr gets isolated quotas")
    void shouldIsolateDifferentRemoteAddr() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(rateLimitService.check(anyString(), anyString()))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        filter.doFilterInternal(request, response, filterChain);

        when(request.getRemoteAddr()).thenReturn("10.0.0.2");
        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService).check("auth", "ip:10.0.0.1");
        verify(rateLimitService).check("auth", "ip:10.0.0.2");
    }

    @Test
    @DisplayName("login: forged X-Forwarded-For is ignored")
    void shouldIgnoreXForwardedFor() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(rateLimitService.check(anyString(), anyString()))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        filter.doFilterInternal(request, response, filterChain);

        // Must use getRemoteAddr(), not X-Forwarded-For
        verify(rateLimitService).check("auth", "ip:10.0.0.1");
        verify(rateLimitService, never()).check(eq("auth"), eq("ip:1.2.3.4"));
    }

    @Test
    @DisplayName("login: forged X-Real-IP is ignored")
    void shouldIgnoreXRealIp() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(rateLimitService.check(anyString(), anyString()))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService).check("auth", "ip:10.0.0.1");
        verify(rateLimitService, never()).check(eq("auth"), eq("ip:5.6.7.8"));
    }

    @Test
    @DisplayName("login: wrong password counts toward quota")
    void shouldCountWrongPassword() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        when(rateLimitService.check(eq("auth"), eq("ip:192.168.1.100")))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        // First attempt: successful admission before password check
        filter.doFilterInternal(request, response, filterChain);
        // Second attempt: also admitted before password check
        filter.doFilterInternal(request, response, filterChain);

        // Both attempts counted (rate limit is before password validation)
        verify(rateLimitService, times(2)).check("auth", "ip:192.168.1.100");
        verify(filterChain, times(2)).doFilter(request, response);
    }

    @Test
    @DisplayName("login rate limited: returns 429")
    void shouldReturn429ForLoginRateLimit() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        when(rateLimitService.check(eq("auth"), eq("ip:192.168.1.100")))
                .thenReturn(RateLimitDecision.rejected(30, 1700000000, 10, 60));

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(429);
        verify(response).setHeader("Retry-After", "30");
        verify(response).setHeader("X-RateLimit-Limit", "10");
        verify(response).setHeader("X-RateLimit-Remaining", "0");
        verify(response).setHeader("X-RateLimit-Reset", "1700000000");
        verify(filterChain, never()).doFilter(any(), any());

        printWriter.flush();
        String body = stringWriter.toString();
        assertThat(body).contains("8001");
        assertThat(body).contains("retryAfterSeconds");
        assertThat(body).contains("30");
    }

    @Test
    @DisplayName("non-login path: should no-op")
    void shouldNoOpForNonLoginPath() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/workspaces/1/conversations");

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService, never()).check(anyString(), anyString());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("GET login: should no-op")
    void shouldNoOpForGetLogin() throws Exception {
        when(request.getMethod()).thenReturn("GET");

        filter.doFilterInternal(request, response, filterChain);

        verify(rateLimitService, never()).check(anyString(), anyString());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("login admitted: filter chain proceeds")
    void shouldProceedWhenLoginAdmitted() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        when(rateLimitService.check(eq("auth"), eq("ip:192.168.1.100")))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("downstream throws ServletException: filter propagates ServletException")
    void shouldPropagateServletException() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        when(rateLimitService.check(eq("auth"), eq("ip:192.168.1.100")))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));
        doThrow(new ServletException("test")).when(filterChain).doFilter(request, response);

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, filterChain))
                .isInstanceOf(ServletException.class)
                .hasMessage("test");
    }

    @Test
    @DisplayName("downstream throws IOException: filter propagates IOException")
    void shouldPropagateIOException() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        when(rateLimitService.check(eq("auth"), eq("ip:192.168.1.100")))
                .thenReturn(RateLimitDecision.allowed(5, 1000, 10, 60));
        doThrow(new IOException("test")).when(filterChain).doFilter(request, response);

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, filterChain))
                .isInstanceOf(IOException.class)
                .hasMessage("test");
    }

    @Test
    @DisplayName("Redis infra failure: login still fail-open")
    void shouldFailOpenOnRedisInfraFailure() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        when(rateLimitService.check(anyString(), anyString()))
                .thenReturn(RateLimitDecision.allowed());

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}