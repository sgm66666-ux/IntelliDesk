package com.intellidesk.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.common.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String LOGIN_PATH = "/api/auth/login";

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimitService rateLimitService, ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Only intercept POST /api/auth/login
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !LOGIN_PATH.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String remoteAddr = request.getRemoteAddr();
        String principal = "ip:" + remoteAddr;

        RateLimitDecision decision = rateLimitService.check("auth", principal);
        if (!decision.isAllowed()) {
            log.warn("Login rate limit exceeded: ip={}, limit={}", remoteAddr, decision.getLimit());
            sendRateLimited(response, decision);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void sendRateLimited(HttpServletResponse response, RateLimitDecision decision) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", String.valueOf(decision.getRetryAfterSeconds()));
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.getLimit()));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setHeader("X-RateLimit-Reset", String.valueOf(decision.getResetEpochSeconds()));

        Map<String, Object> data = Map.of(
                "retryAfterSeconds", decision.getRetryAfterSeconds(),
                "limit", decision.getLimit(),
                "windowSeconds", decision.getWindowSeconds()
        );
        Result<Map<String, Object>> result = Result.error(ErrorCode.RATE_LIMITED.getCode(),
                ErrorCode.RATE_LIMITED.getMessage(), data);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}