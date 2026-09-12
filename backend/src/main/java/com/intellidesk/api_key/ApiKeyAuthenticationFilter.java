package com.intellidesk.api_key;

import com.intellidesk.common.Result;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String KEY_PREFIX = "isk";

    private final ApiKeyService apiKeyService;
    private final ApiKeyMapper apiKeyMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService,
                                       ApiKeyMapper apiKeyMapper,
                                       PasswordEncoder passwordEncoder,
                                       UserService userService,
                                       WorkspaceAuthorizationService workspaceAuthorizationService,
                                       ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.apiKeyMapper = apiKeyMapper;
        this.passwordEncoder = passwordEncoder;
        this.userService = userService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws IOException {
        String apiKeyHeader = request.getHeader(API_KEY_HEADER);

        if (!StringUtils.hasText(apiKeyHeader)) {
            try {
                filterChain.doFilter(request, response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return;
        }

        String bearerHeader = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerHeader) && bearerHeader.startsWith("Bearer ")) {
            log.warn("Credential conflict: both X-API-Key and Authorization:Bearer present");
            sendError(response, com.intellidesk.common.ErrorCode.API_KEY_CREDENTIAL_CONFLICT);
            return;
        }

        try {
            authenticate(apiKeyHeader, request);
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.warn("API Key authentication failed: reason={}", e.getMessage());
            sendError(response, com.intellidesk.common.ErrorCode.API_KEY_INVALID);
        }
    }

    private void authenticate(String apiKeyHeader, HttpServletRequest request) {
        String[] parts = apiKeyHeader.split("_", 3);
        if (parts.length != 3 || !KEY_PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException("UNKNOWN");
        }

        String prefix = parts[1];
        String secret = parts[2];

        if (prefix.isEmpty() || secret.isEmpty()) {
            throw new IllegalArgumentException("UNKNOWN");
        }

        ApiKey apiKey = apiKeyService.findByKeyPrefix(prefix);
        if (apiKey == null) {
            throw new IllegalArgumentException("UNKNOWN");
        }

        if (!"ACTIVE".equals(apiKey.getStatus())) {
            throw new IllegalArgumentException("REVOKED");
        }

        if (!passwordEncoder.matches(secret, apiKey.getKeyHash())) {
            throw new IllegalArgumentException("UNKNOWN");
        }

        if (apiKey.getExpiresAt() != null && !apiKey.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("EXPIRED");
        }

        User user = userService.getById(apiKey.getUserId());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new IllegalArgumentException("DISABLED");
        }

        if (!workspaceAuthorizationService.isMember(apiKey.getWorkspaceId(), user.getId())) {
            throw new IllegalArgumentException("OWNER_REMOVED");
        }

        List<String> roleCodes = userService.getRoleCodes(user.getId());
        List<String> permissionCodes = userService.getPermissionCodes(user.getId());

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.addAll(roleCodes.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList());
        authorities.addAll(permissionCodes.stream()
                .map(SimpleGrantedAuthority::new)
                .toList());

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, authorities);
        authentication.setDetails(new ApiKeyAuthenticationDetails(
                apiKey.getId(), apiKey.getWorkspaceId(), apiKey.getScope(), ApiKeyAuthenticationDetails.AUTH_TYPE_API_KEY));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        apiKeyMapper.updateLastUsedAt(apiKey.getId(), LocalDateTime.now());
    }

    private void sendError(HttpServletResponse response, com.intellidesk.common.ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Result<Void> result = Result.error(errorCode);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}