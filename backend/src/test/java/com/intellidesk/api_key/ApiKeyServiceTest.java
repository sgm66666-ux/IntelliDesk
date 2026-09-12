package com.intellidesk.api_key;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intellidesk.api_key.dto.ApiKeyResponse;
import com.intellidesk.api_key.dto.CreateApiKeyRequest;
import com.intellidesk.api_key.dto.CreateApiKeyResponse;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyMapper apiKeyMapper;

    @Mock
    private ApiKeyGenerator apiKeyGenerator;

    @Mock
    private WorkspaceAuthorizationService workspaceAuthorizationService;

    private ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        apiKeyService = new ApiKeyService(apiKeyGenerator, workspaceAuthorizationService);
        ReflectionTestUtils.setField(apiKeyService, "baseMapper", apiKeyMapper);
    }

    @Test
    void shouldCreateApiKeySuccessfully() {
        CreateApiKeyRequest request = new CreateApiKeyRequest();
        request.setName("Test Key");
        request.setScope("READ");

        ApiKeyGenerator.GeneratedKey generated = new ApiKeyGenerator.GeneratedKey(
                "abc12345", "secret123", "isk_abc12345_secret123", "$2a$10$hash");

        when(apiKeyGenerator.generate()).thenReturn(generated);
        when(apiKeyMapper.insert(any(ApiKey.class))).thenReturn(1);

        CreateApiKeyResponse response = apiKeyService.create(1L, 100L, request);

        assertThat(response.getName()).isEqualTo("Test Key");
        assertThat(response.getFullKey()).isEqualTo("isk_abc12345_secret123");
        assertThat(response.getKeyPrefix()).isEqualTo("abc12345");
        assertThat(response.getScope()).isEqualTo("READ");

        verify(workspaceAuthorizationService).requireOwner(1L, 100L);

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyMapper).insert(captor.capture());
        ApiKey saved = captor.getValue();
        assertThat(saved.getKeyHash()).isEqualTo("$2a$10$hash");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void shouldCreateApiKeyWithDefaultScope() {
        CreateApiKeyRequest request = new CreateApiKeyRequest();
        request.setName("Test Key");

        ApiKeyGenerator.GeneratedKey generated = new ApiKeyGenerator.GeneratedKey(
                "abc12345", "secret123", "isk_abc12345_secret123", "$2a$10$hash");

        when(apiKeyGenerator.generate()).thenReturn(generated);
        when(apiKeyMapper.insert(any(ApiKey.class))).thenReturn(1);

        CreateApiKeyResponse response = apiKeyService.create(1L, 100L, request);

        assertThat(response.getScope()).isEqualTo("READ");
    }

    @Test
    void shouldRejectInvalidScope() {
        CreateApiKeyRequest request = new CreateApiKeyRequest();
        request.setName("Test Key");
        request.setScope("WRITE");

        assertThatThrownBy(() -> apiKeyService.create(1L, 100L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.API_KEY_SCOPE_INVALID.getCode());
    }

    @Test
    void shouldRejectPastExpiresAt() {
        CreateApiKeyRequest request = new CreateApiKeyRequest();
        request.setName("Test Key");
        request.setExpiresAt(LocalDateTime.now().minusDays(1));

        assertThatThrownBy(() -> apiKeyService.create(1L, 100L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.API_KEY_EXPIRES_AT_INVALID.getCode());
    }

    @Test
    void shouldListApiKeys() {
        ApiKey key1 = new ApiKey();
        key1.setId(1L);
        key1.setWorkspaceId(1L);
        key1.setName("Key 1");
        key1.setKeyPrefix("abcd1234");
        key1.setScope("READ");
        key1.setStatus("ACTIVE");
        key1.setCreatedAt(LocalDateTime.now());

        ApiKey key2 = new ApiKey();
        key2.setId(2L);
        key2.setWorkspaceId(1L);
        key2.setName("Key 2");
        key2.setKeyPrefix("efgh5678");
        key2.setScope("READ");
        key2.setStatus("REVOKED");
        key2.setCreatedAt(LocalDateTime.now());
        key2.setRevokedAt(LocalDateTime.now());

        when(apiKeyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(key1, key2));

        List<ApiKeyResponse> responses = apiKeyService.list(1L, 100L);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getName()).isEqualTo("Key 1");
        assertThat(responses.get(1).getStatus()).isEqualTo("REVOKED");
        assertThat(responses.get(1).getEffectiveStatus()).isEqualTo("REVOKED");

        verify(workspaceAuthorizationService).requireOwner(1L, 100L);
    }

    @Test
    void shouldDetailApiKey() {
        ApiKey key = new ApiKey();
        key.setId(1L);
        key.setWorkspaceId(1L);
        key.setName("Key 1");
        key.setKeyPrefix("abcd1234");
        key.setScope("READ");
        key.setStatus("ACTIVE");
        key.setCreatedAt(LocalDateTime.now());

        when(apiKeyMapper.selectById(1L)).thenReturn(key);

        ApiKeyResponse response = apiKeyService.detail(1L, 1L, 100L);

        assertThat(response.getName()).isEqualTo("Key 1");
        assertThat(response.getEffectiveStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void shouldDetailExpiredKey() {
        ApiKey key = new ApiKey();
        key.setId(1L);
        key.setWorkspaceId(1L);
        key.setName("Key 1");
        key.setKeyPrefix("abcd1234");
        key.setScope("READ");
        key.setStatus("ACTIVE");
        key.setExpiresAt(LocalDateTime.now().minusDays(1));
        key.setCreatedAt(LocalDateTime.now());

        when(apiKeyMapper.selectById(1L)).thenReturn(key);

        ApiKeyResponse response = apiKeyService.detail(1L, 1L, 100L);

        assertThat(response.getEffectiveStatus()).isEqualTo("EXPIRED");
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void shouldRevokeApiKey() {
        ApiKey key = new ApiKey();
        key.setId(1L);
        key.setWorkspaceId(1L);
        key.setStatus("ACTIVE");
        key.setKeyPrefix("abcd1234");

        when(apiKeyMapper.selectById(1L)).thenReturn(key);
        when(apiKeyMapper.updateById(any(ApiKey.class))).thenReturn(1);

        apiKeyService.revoke(1L, 1L, 100L);

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("REVOKED");
        assertThat(captor.getValue().getRevokedAt()).isNotNull();
    }

    @Test
    void shouldRevokeIdempotent() {
        ApiKey key = new ApiKey();
        key.setId(1L);
        key.setWorkspaceId(1L);
        key.setStatus("REVOKED");
        key.setKeyPrefix("abcd1234");

        when(apiKeyMapper.selectById(1L)).thenReturn(key);

        apiKeyService.revoke(1L, 1L, 100L);

        verify(apiKeyMapper, never()).updateById(any(ApiKey.class));
    }

    @Test
    void shouldReturn404ForCrossWorkspaceKey() {
        ApiKey key = new ApiKey();
        key.setId(1L);
        key.setWorkspaceId(2L);

        when(apiKeyMapper.selectById(1L)).thenReturn(key);

        assertThatThrownBy(() -> apiKeyService.detail(1L, 1L, 100L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.API_KEY_NOT_FOUND.getCode());
    }

    @Test
    void shouldListNotContainSecret() {
        ApiKey key = new ApiKey();
        key.setId(1L);
        key.setWorkspaceId(1L);
        key.setName("Key 1");
        key.setKeyPrefix("abcd1234");
        key.setKeyHash("should-not-appear");
        key.setScope("READ");
        key.setStatus("ACTIVE");
        key.setCreatedAt(LocalDateTime.now());

        when(apiKeyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(key));

        List<ApiKeyResponse> responses = apiKeyService.list(1L, 100L);

        assertThat(responses).hasSize(1);
        ApiKeyResponse r = responses.get(0);
        String json = r.toString();
        assertThat(json).doesNotContain("should-not-appear");
        assertThat(json).doesNotContain("keyHash");
    }
}