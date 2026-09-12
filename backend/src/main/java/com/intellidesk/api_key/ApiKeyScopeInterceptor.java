package com.intellidesk.api_key;

import com.intellidesk.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class ApiKeyScopeInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyScopeInterceptor.class);

    private static final Set<String> READ_ALLOWLIST = Set.of(
            "POST /api/workspaces/{workspaceId}/conversations",
            "GET /api/workspaces/{workspaceId}/conversations",
            "GET /api/workspaces/{workspaceId}/conversations/{conversationId}",
            "GET /api/workspaces/{workspaceId}/conversations/{conversationId}/messages",
            "GET /api/workspaces/{workspaceId}/knowledge-bases",
            "GET /api/workspaces/{workspaceId}/knowledge-bases/{knowledgeBaseId}",
            "GET /api/workspaces/{workspaceId}/knowledge-bases/{knowledgeBaseId}/documents",
            "GET /api/workspaces/{workspaceId}/knowledge-bases/{knowledgeBaseId}/documents/{documentId}",
            "GET /api/workspaces/{workspaceId}/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/chunks",
            "POST /api/workspaces/{workspaceId}/retrieval/search",
            "POST /api/workspaces/{workspaceId}/conversations/{conversationId}/agent/stream"
    );

    private final ObjectMapper objectMapper;

    public ApiKeyScopeInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return true;
        }

        Object details = authentication.getDetails();
        if (!(details instanceof ApiKeyAuthenticationDetails apiKeyDetails)) {
            return true;
        }

        if (!ApiKeyAuthenticationDetails.AUTH_TYPE_API_KEY.equals(apiKeyDetails.authenticationType())) {
            return true;
        }

        Map<String, String> pathVariables = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (pathVariables == null || !pathVariables.containsKey("workspaceId")) {
            return true;
        }

        Long requestWorkspaceId = Long.parseLong(pathVariables.get("workspaceId"));
        if (!requestWorkspaceId.equals(apiKeyDetails.workspaceId())) {
            log.warn("API Key cross-workspace access: keyWorkspace={}, requestWorkspace={}",
                    apiKeyDetails.workspaceId(), requestWorkspaceId);
            sendError(response, 404, com.intellidesk.common.ErrorCode.API_KEY_NOT_FOUND);
            return false;
        }

        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String method = request.getMethod();
        String key = method + " " + pattern;

        if (!READ_ALLOWLIST.contains(key)) {
            log.warn("API Key scope denied: keyId={}, endpoint={}", apiKeyDetails.apiKeyId(), key);
            sendError(response, 403, com.intellidesk.common.ErrorCode.API_KEY_SCOPE_DENIED);
            return false;
        }

        return true;
    }

    private void sendError(HttpServletResponse response, int status,
                           com.intellidesk.common.ErrorCode errorCode) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Result<Void> result = Result.error(errorCode);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}