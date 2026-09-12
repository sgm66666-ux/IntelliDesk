package com.intellidesk.api_key;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.common.Result;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationFilterTest {

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private UserService userService;

    @Mock
    private WorkspaceAuthorizationService workspaceAuthorizationService;

    @Mock
    private ApiKeyMapper apiKeyMapper;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private PasswordEncoder passwordEncoder;
    private ApiKeyAuthenticationFilter filter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        passwordEncoder = new BCryptPasswordEncoder();
        objectMapper = new ObjectMapper();
        filter = new ApiKeyAuthenticationFilter(apiKeyService, apiKeyMapper, passwordEncoder,
                userService, workspaceAuthorizationService, objectMapper);
    }

    // --- Case A: No X-API-Key header ---

    @Test
    void shouldPassThroughWhenNoApiKeyHeader() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // --- Case C: Invalid key ---

    @Test
    void shouldReturn401ForInvalidFormat() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn("not-a-valid-key");
        when(request.getHeader("Authorization")).thenReturn(null);
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void shouldReturn401ForUnknownPrefix() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn("isk_unknown_1234567890abcdef");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(apiKeyService.findByKeyPrefix("unknown")).thenReturn(null);
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void shouldReturn401ForWrongSecret() throws Exception {
        ApiKey apiKey = new ApiKey();
        apiKey.setId(1L);
        apiKey.setWorkspaceId(1L);
        apiKey.setUserId(100L);
        apiKey.setKeyPrefix("abcd1234");
        apiKey.setKeyHash(passwordEncoder.encode("correct-secret"));
        apiKey.setScope("READ");
        apiKey.setStatus("ACTIVE");

        when(request.getHeader("X-API-Key")).thenReturn("isk_abcd1234_wrong-secret");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(apiKeyService.findByKeyPrefix("abcd1234")).thenReturn(apiKey);
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void shouldReturn401ForExpiredKey() throws Exception {
        String secret = "correct-secret";
        ApiKey apiKey = new ApiKey();
        apiKey.setId(1L);
        apiKey.setWorkspaceId(1L);
        apiKey.setUserId(100L);
        apiKey.setKeyPrefix("abcd1234");
        apiKey.setKeyHash(passwordEncoder.encode(secret));
        apiKey.setScope("READ");
        apiKey.setStatus("ACTIVE");
        apiKey.setExpiresAt(LocalDateTime.now().minusHours(1));

        when(request.getHeader("X-API-Key")).thenReturn("isk_abcd1234_" + secret);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(apiKeyService.findByKeyPrefix("abcd1234")).thenReturn(apiKey);
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void shouldReturn401ForRevokedKey() throws Exception {
        String secret = "correct-secret";
        ApiKey apiKey = new ApiKey();
        apiKey.setId(1L);
        apiKey.setWorkspaceId(1L);
        apiKey.setUserId(100L);
        apiKey.setKeyPrefix("abcd1234");
        apiKey.setKeyHash(passwordEncoder.encode(secret));
        apiKey.setScope("READ");
        apiKey.setStatus("REVOKED");

        when(request.getHeader("X-API-Key")).thenReturn("isk_abcd1234_" + secret);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(apiKeyService.findByKeyPrefix("abcd1234")).thenReturn(apiKey);
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void shouldReturn401ForDisabledOwner() throws Exception {
        String secret = "correct-secret";
        ApiKey apiKey = new ApiKey();
        apiKey.setId(1L);
        apiKey.setWorkspaceId(1L);
        apiKey.setUserId(100L);
        apiKey.setKeyPrefix("abcd1234");
        apiKey.setKeyHash(passwordEncoder.encode(secret));
        apiKey.setScope("READ");
        apiKey.setStatus("ACTIVE");

        User disabledUser = new User();
        disabledUser.setId(100L);
        disabledUser.setUsername("disabled");
        disabledUser.setStatus(0);

        when(request.getHeader("X-API-Key")).thenReturn("isk_abcd1234_" + secret);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(apiKeyService.findByKeyPrefix("abcd1234")).thenReturn(apiKey);
        when(userService.getById(100L)).thenReturn(disabledUser);
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void shouldReturn401ForOwnerRemovedFromWorkspace() throws Exception {
        String secret = "correct-secret";
        ApiKey apiKey = new ApiKey();
        apiKey.setId(1L);
        apiKey.setWorkspaceId(1L);
        apiKey.setUserId(100L);
        apiKey.setKeyPrefix("abcd1234");
        apiKey.setKeyHash(passwordEncoder.encode(secret));
        apiKey.setScope("READ");
        apiKey.setStatus("ACTIVE");

        User activeUser = new User();
        activeUser.setId(100L);
        activeUser.setUsername("testuser");
        activeUser.setStatus(1);

        when(request.getHeader("X-API-Key")).thenReturn("isk_abcd1234_" + secret);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(apiKeyService.findByKeyPrefix("abcd1234")).thenReturn(apiKey);
        when(userService.getById(100L)).thenReturn(activeUser);
        when(workspaceAuthorizationService.isMember(1L, 100L)).thenReturn(false);
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    // --- Credential Conflict ---

    @Test
    void shouldReturn401ForCredentialConflict() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn("isk_abc12345_secret");
        when(request.getHeader("Authorization")).thenReturn("Bearer jwt-token");
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    // --- Valid API Key ---

    @Test
    void shouldAuthenticateValidApiKey() throws Exception {
        String secret = "correct-secret";
        ApiKey apiKey = new ApiKey();
        apiKey.setId(1L);
        apiKey.setWorkspaceId(1L);
        apiKey.setUserId(100L);
        apiKey.setKeyPrefix("abcd1234");
        apiKey.setKeyHash(passwordEncoder.encode(secret));
        apiKey.setScope("READ");
        apiKey.setStatus("ACTIVE");

        User activeUser = new User();
        activeUser.setId(100L);
        activeUser.setUsername("testuser");
        activeUser.setStatus(1);

        when(request.getHeader("X-API-Key")).thenReturn("isk_abcd1234_" + secret);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(apiKeyService.findByKeyPrefix("abcd1234")).thenReturn(apiKey);
        when(userService.getById(100L)).thenReturn(activeUser);
        when(workspaceAuthorizationService.isMember(1L, 100L)).thenReturn(true);
        when(userService.getRoleCodes(100L)).thenReturn(List.of("MEMBER"));
        when(userService.getPermissionCodes(100L)).thenReturn(List.of("conversation:view"));
        when(apiKeyMapper.updateLastUsedAt(eq(1L), any(LocalDateTime.class))).thenReturn(1);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo("testuser");
        assertThat(auth.getCredentials()).isNull();
        assertThat(auth.getAuthorities()).isNotEmpty();

        assertThat(auth.getDetails()).isInstanceOf(ApiKeyAuthenticationDetails.class);
        ApiKeyAuthenticationDetails details = (ApiKeyAuthenticationDetails) auth.getDetails();
        assertThat(details.apiKeyId()).isEqualTo(1L);
        assertThat(details.workspaceId()).isEqualTo(1L);
        assertThat(details.scope()).isEqualTo("READ");
        assertThat(details.authenticationType()).isEqualTo("API_KEY");
    }

    @Test
    void shouldUpdateLastUsedAtOnSuccessfulAuth() throws Exception {
        String secret = "correct-secret";
        ApiKey apiKey = new ApiKey();
        apiKey.setId(1L);
        apiKey.setWorkspaceId(1L);
        apiKey.setUserId(100L);
        apiKey.setKeyPrefix("abcd1234");
        apiKey.setKeyHash(passwordEncoder.encode(secret));
        apiKey.setScope("READ");
        apiKey.setStatus("ACTIVE");

        User activeUser = new User();
        activeUser.setId(100L);
        activeUser.setUsername("testuser");
        activeUser.setStatus(1);

        when(request.getHeader("X-API-Key")).thenReturn("isk_abcd1234_" + secret);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(apiKeyService.findByKeyPrefix("abcd1234")).thenReturn(apiKey);
        when(userService.getById(100L)).thenReturn(activeUser);
        when(workspaceAuthorizationService.isMember(1L, 100L)).thenReturn(true);
        when(userService.getRoleCodes(100L)).thenReturn(List.of("MEMBER"));
        when(userService.getPermissionCodes(100L)).thenReturn(List.of("conversation:view"));
        when(apiKeyMapper.updateLastUsedAt(eq(1L), any(LocalDateTime.class))).thenReturn(1);

        filter.doFilterInternal(request, response, filterChain);

        verify(apiKeyMapper).updateLastUsedAt(eq(1L), any(LocalDateTime.class));
    }

    @Test
    void shouldNotUpdateLastUsedAtOnFailedAuth() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn("isk_unknown_bad");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(apiKeyService.findByKeyPrefix("unknown")).thenReturn(null);
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        filter.doFilterInternal(request, response, filterChain);

        verify(apiKeyMapper, never()).updateLastUsedAt(anyLong(), any());
    }

    @Test
    void shouldNotFallbackToJwtWhenInvalidApiKey() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn("isk_unknown_bad");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(apiKeyService.findByKeyPrefix("unknown")).thenReturn(null);
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        verify(response).setStatus(401);
    }

    @Test
    void shouldNotStoreCredentialsInSecurityContext() throws Exception {
        String secret = "correct-secret";
        ApiKey apiKey = new ApiKey();
        apiKey.setId(1L);
        apiKey.setWorkspaceId(1L);
        apiKey.setUserId(100L);
        apiKey.setKeyPrefix("abcd1234");
        apiKey.setKeyHash(passwordEncoder.encode(secret));
        apiKey.setScope("READ");
        apiKey.setStatus("ACTIVE");

        User activeUser = new User();
        activeUser.setId(100L);
        activeUser.setUsername("testuser");
        activeUser.setStatus(1);

        when(request.getHeader("X-API-Key")).thenReturn("isk_abcd1234_" + secret);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(apiKeyService.findByKeyPrefix("abcd1234")).thenReturn(apiKey);
        when(userService.getById(100L)).thenReturn(activeUser);
        when(workspaceAuthorizationService.isMember(1L, 100L)).thenReturn(true);
        when(userService.getRoleCodes(100L)).thenReturn(List.of("MEMBER"));
        when(userService.getPermissionCodes(100L)).thenReturn(List.of("conversation:view"));
        when(apiKeyMapper.updateLastUsedAt(eq(1L), any(LocalDateTime.class))).thenReturn(1);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getCredentials()).isNull();
    }
}