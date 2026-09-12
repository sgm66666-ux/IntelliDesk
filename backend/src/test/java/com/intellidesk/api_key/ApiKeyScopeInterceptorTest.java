package com.intellidesk.api_key;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyScopeInterceptorTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private ApiKeyScopeInterceptor interceptor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        objectMapper = new ObjectMapper();
        interceptor = new ApiKeyScopeInterceptor(objectMapper);
    }

    // --- JWT: no-op ---

    @Test
    void shouldPassThroughForJwtAuthentication() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", null, List.of()));

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isTrue();
    }

    @Test
    void shouldPassThroughForNullAuthentication() throws Exception {
        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isTrue();
    }

    // --- Allowed endpoints ---

    @Test
    void shouldAllowReadEndpoint() throws Exception {
        setupApiKeyAuth();
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                .thenReturn(Map.of("workspaceId", "1"));
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/knowledge-bases");
        when(request.getMethod()).thenReturn("GET");

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isTrue();
    }

    @Test
    void shouldAllowAgentEndpoint() throws Exception {
        setupApiKeyAuth();
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                .thenReturn(Map.of("workspaceId", "1", "conversationId", "5"));
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/conversations/{conversationId}/agent/stream");
        when(request.getMethod()).thenReturn("POST");

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isTrue();
    }

    @Test
    void shouldAllowRetrievalSearchEndpoint() throws Exception {
        setupApiKeyAuth();
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                .thenReturn(Map.of("workspaceId", "1"));
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/retrieval/search");
        when(request.getMethod()).thenReturn("POST");

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isTrue();
    }

    @Test
    void shouldAllowConversationCreateEndpoint() throws Exception {
        setupApiKeyAuth();
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                .thenReturn(Map.of("workspaceId", "1"));
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/conversations");
        when(request.getMethod()).thenReturn("POST");

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isTrue();
    }

    // --- Denied endpoints ---

    @Test
    void shouldDenyKbCreateEndpoint() throws Exception {
        setupApiKeyAuth();
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                .thenReturn(Map.of("workspaceId", "1"));
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/knowledge-bases");
        when(request.getMethod()).thenReturn("POST");
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isFalse();
        verify(response).setStatus(403);
    }

    @Test
    void shouldDenyApiKeyManagementEndpoint() throws Exception {
        setupApiKeyAuth();
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                .thenReturn(Map.of("workspaceId", "1"));
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/api-keys");
        when(request.getMethod()).thenReturn("GET");
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isFalse();
        verify(response).setStatus(403);
    }

    @Test
    void shouldDenyDocumentUploadEndpoint() throws Exception {
        setupApiKeyAuth();
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                .thenReturn(Map.of("workspaceId", "1", "knowledgeBaseId", "1"));
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workspaces/{workspaceId}/knowledge-bases/{knowledgeBaseId}/documents");
        when(request.getMethod()).thenReturn("POST");
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isFalse();
        verify(response).setStatus(403);
    }

    // --- Cross-workspace ---

    @Test
    void shouldReturn404ForCrossWorkspace() throws Exception {
        setupApiKeyAuth();
        when(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE))
                .thenReturn(Map.of("workspaceId", "2"));
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        boolean result = interceptor.preHandle(request, response, null);

        assertThat(result).isFalse();
        verify(response).setStatus(404);
    }

    private void setupApiKeyAuth() {
        ApiKeyAuthenticationDetails details = new ApiKeyAuthenticationDetails(
                1L, 1L, "READ", "API_KEY");
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("testuser", null, List.of());
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}