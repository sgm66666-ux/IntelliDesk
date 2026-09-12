package com.intellidesk.api_key.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.intellidesk.api_key.ApiKey;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiKeyResponse {

    private Long id;
    private Long workspaceId;
    private String name;
    private String keyPrefix;
    private String scope;
    private String status;
    private String effectiveStatus;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime revokedAt;

    public static ApiKeyResponse from(ApiKey apiKey) {
        ApiKeyResponse resp = new ApiKeyResponse();
        resp.id = apiKey.getId();
        resp.workspaceId = apiKey.getWorkspaceId();
        resp.name = apiKey.getName();
        resp.keyPrefix = apiKey.getKeyPrefix();
        resp.scope = apiKey.getScope();
        resp.status = apiKey.getStatus();
        resp.effectiveStatus = computeEffectiveStatus(apiKey);
        resp.expiresAt = apiKey.getExpiresAt();
        resp.lastUsedAt = apiKey.getLastUsedAt();
        resp.createdAt = apiKey.getCreatedAt();
        resp.revokedAt = apiKey.getRevokedAt();
        return resp;
    }

    private static String computeEffectiveStatus(ApiKey apiKey) {
        if ("REVOKED".equals(apiKey.getStatus())) {
            return "REVOKED";
        }
        if (apiKey.getExpiresAt() != null && !apiKey.getExpiresAt().isAfter(LocalDateTime.now())) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    public Long getId() { return id; }
    public Long getWorkspaceId() { return workspaceId; }
    public String getName() { return name; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getScope() { return scope; }
    public String getStatus() { return status; }
    public String getEffectiveStatus() { return effectiveStatus; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
}