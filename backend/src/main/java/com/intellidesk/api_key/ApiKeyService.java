package com.intellidesk.api_key;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.intellidesk.api_key.dto.ApiKeyResponse;
import com.intellidesk.api_key.dto.CreateApiKeyRequest;
import com.intellidesk.api_key.dto.CreateApiKeyResponse;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ApiKeyService extends ServiceImpl<ApiKeyMapper, ApiKey> {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final ApiKeyGenerator apiKeyGenerator;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;

    public ApiKeyService(ApiKeyGenerator apiKeyGenerator,
                         WorkspaceAuthorizationService workspaceAuthorizationService) {
        this.apiKeyGenerator = apiKeyGenerator;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
    }

    @Transactional
    public CreateApiKeyResponse create(Long workspaceId, Long userId, CreateApiKeyRequest request) {
        workspaceAuthorizationService.requireOwner(workspaceId, userId);

        if (request.getScope() != null && !"READ".equals(request.getScope())) {
            throw new BusinessException(ErrorCode.API_KEY_SCOPE_INVALID);
        }

        if (request.getExpiresAt() != null && !request.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.API_KEY_EXPIRES_AT_INVALID);
        }

        ApiKeyGenerator.GeneratedKey generated = apiKeyGenerator.generate();

        ApiKey apiKey = new ApiKey();
        apiKey.setWorkspaceId(workspaceId);
        apiKey.setUserId(userId);
        apiKey.setName(request.getName());
        apiKey.setKeyPrefix(generated.prefix());
        apiKey.setKeyHash(generated.keyHash());
        apiKey.setScope(request.getScope() != null ? request.getScope() : "READ");
        apiKey.setStatus("ACTIVE");
        apiKey.setExpiresAt(request.getExpiresAt());
        apiKey.setCreatedAt(LocalDateTime.now());

        save(apiKey);

        log.info("API Key created: id={}, prefix={}, workspace={}, user={}",
                apiKey.getId(), apiKey.getKeyPrefix(), workspaceId, userId);

        return CreateApiKeyResponse.from(apiKey, generated.fullKey());
    }

    public List<ApiKeyResponse> list(Long workspaceId, Long userId) {
        workspaceAuthorizationService.requireOwner(workspaceId, userId);

        List<ApiKey> keys = baseMapper.selectList(
                new LambdaQueryWrapper<ApiKey>()
                        .eq(ApiKey::getWorkspaceId, workspaceId)
                        .orderByDesc(ApiKey::getCreatedAt));

        return keys.stream().map(ApiKeyResponse::from).toList();
    }

    public ApiKeyResponse detail(Long workspaceId, Long keyId, Long userId) {
        workspaceAuthorizationService.requireOwner(workspaceId, userId);

        ApiKey apiKey = getById(keyId);
        if (apiKey == null || !apiKey.getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(ErrorCode.API_KEY_NOT_FOUND);
        }

        return ApiKeyResponse.from(apiKey);
    }

    @Transactional
    public void revoke(Long workspaceId, Long keyId, Long userId) {
        workspaceAuthorizationService.requireOwner(workspaceId, userId);

        ApiKey apiKey = getById(keyId);
        if (apiKey == null || !apiKey.getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(ErrorCode.API_KEY_NOT_FOUND);
        }

        if ("REVOKED".equals(apiKey.getStatus())) {
            log.info("API Key already revoked: id={}, prefix={}", keyId, apiKey.getKeyPrefix());
            return;
        }

        apiKey.setStatus("REVOKED");
        apiKey.setRevokedAt(LocalDateTime.now());
        updateById(apiKey);

        log.info("API Key revoked: id={}, prefix={}, workspace={}", keyId, apiKey.getKeyPrefix(), workspaceId);
    }

    public ApiKey findByKeyPrefix(String keyPrefix) {
        return baseMapper.selectOne(
                new LambdaQueryWrapper<ApiKey>()
                        .eq(ApiKey::getKeyPrefix, keyPrefix)
                        .eq(ApiKey::getStatus, "ACTIVE"));
    }

    public ApiKey getScopedKey(Long workspaceId, Long keyId) {
        ApiKey apiKey = getById(keyId);
        if (apiKey == null || !apiKey.getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(ErrorCode.API_KEY_NOT_FOUND);
        }
        return apiKey;
    }
}