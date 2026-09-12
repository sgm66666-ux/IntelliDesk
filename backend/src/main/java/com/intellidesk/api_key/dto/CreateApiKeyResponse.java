package com.intellidesk.api_key.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.intellidesk.api_key.ApiKey;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateApiKeyResponse {

    private Long id;
    private Long workspaceId;
    private String name;
    private String keyPrefix;
    private String scope;
    private String fullKey;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;

    public static CreateApiKeyResponse from(ApiKey apiKey, String fullKey) {
        CreateApiKeyResponse resp = new CreateApiKeyResponse();
        resp.id = apiKey.getId();
        resp.workspaceId = apiKey.getWorkspaceId();
        resp.name = apiKey.getName();
        resp.keyPrefix = apiKey.getKeyPrefix();
        resp.scope = apiKey.getScope();
        resp.fullKey = fullKey;
        resp.expiresAt = apiKey.getExpiresAt();
        resp.createdAt = apiKey.getCreatedAt();
        return resp;
    }

    public Long getId() { return id; }
    public Long getWorkspaceId() { return workspaceId; }
    public String getName() { return name; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getScope() { return scope; }
    public String getFullKey() { return fullKey; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}