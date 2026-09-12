package com.intellidesk.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.api_key.ApiKeyAuthenticationDetails;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.common.Result;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Set;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private static final Set<String> PROTECTED_ROUTES = Set.of(
            "POST /api/workspaces/{workspaceId}/conversations/{conversationId}/agent/stream",
            "POST /api/workspaces/{workspaceId}/conversations/{conversationId}/messages/stream",
            "POST /api/workspaces/{workspaceId}/retrieval/search",
            "POST /api/workspaces/{workspaceId}/knowledge-bases/{knowledgeBaseId}/documents"
    );

    private final RateLimitService rateLimitService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(RateLimitService rateLimitService, UserService userService,
                                ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            return true;
        }

        String method = request.getMethod();
        String routeKey = method + " " + pattern;
        if (!PROTECTED_ROUTES.contains(routeKey)) {
            return true;
        }

        String category = resolveCategory(routeKey);
        String principal = resolvePrincipal();
        if (principal == null) {
            return true;
        }

        RateLimitDecision decision = rateLimitService.check(category, principal);
        if (!decision.isAllowed()) {
            log.warn("Rate limit exceeded: principal={}, category={}, limit={}", principal, category, decision.getLimit());
            sendRateLimited(response, decision);
            return false;
        }

        return true;
    }

    private String resolveCategory(String routeKey) {
        if (routeKey.contains("/agent/stream")) {
            return "agent";
        }
        if (routeKey.contains("/messages/stream")) {
            return "chat";
        }
        if (routeKey.contains("/retrieval/search")) {
            return "retrieval";
        }
        if (routeKey.contains("/documents") && !routeKey.contains("/documents/")) {
            return "upload";
        }
        return null;
    }

    private String resolvePrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object details = authentication.getDetails();
        if (details instanceof ApiKeyAuthenticationDetails apiKeyDetails
                && ApiKeyAuthenticationDetails.AUTH_TYPE_API_KEY.equals(apiKeyDetails.authenticationType())) {
            return "apikey:" + apiKeyDetails.apiKeyId();
        }

        // JWT: use userId (not username/email/token)
        if (authentication.getPrincipal() instanceof String username) {
            User user = userService.findByUsername(username);
            if (user != null) {
                return "user:" + user.getId();
            }
        }

        return null;
    }

    private void sendRateLimited(HttpServletResponse response, RateLimitDecision decision) throws Exception {
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