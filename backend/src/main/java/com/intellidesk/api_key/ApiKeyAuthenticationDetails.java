package com.intellidesk.api_key;

public record ApiKeyAuthenticationDetails(
        Long apiKeyId,
        Long workspaceId,
        String scope,
        String authenticationType
) {
    public static final String AUTH_TYPE_API_KEY = "API_KEY";

    public ApiKeyAuthenticationDetails {
        if (apiKeyId == null || workspaceId == null || authenticationType == null) {
            throw new IllegalArgumentException("apiKeyId, workspaceId and authenticationType must not be null");
        }
    }
}