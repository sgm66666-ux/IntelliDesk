package com.intellidesk.api_key;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intellidesk.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyGeneratorTest {

    @Mock
    private ApiKeyMapper apiKeyMapper;

    private PasswordEncoder passwordEncoder;
    private ApiKeyProperties properties;
    private ApiKeyGenerator generator;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        properties = new ApiKeyProperties();
        properties.setPrefixLength(8);
        properties.setSecretBytes(32);
        properties.setMaxPrefixRetries(5);
        generator = new ApiKeyGenerator(passwordEncoder, properties, apiKeyMapper);
    }

    @Test
    void shouldGenerateCorrectFormat() {
        when(apiKeyMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        ApiKeyGenerator.GeneratedKey key = generator.generate();

        assertThat(key.fullKey()).startsWith("isk_");
        assertThat(key.prefix()).hasSize(8);
        assertThat(key.secret()).isNotEmpty();
        assertThat(key.keyHash()).isNotEmpty();

        String[] parts = key.fullKey().split("_", 3);
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo("isk");
        assertThat(parts[1]).isEqualTo(key.prefix());
        assertThat(parts[2]).isEqualTo(key.secret());
    }

    @Test
    void shouldParseSecretWithUnderscore() {
        when(apiKeyMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        ApiKeyGenerator.GeneratedKey key = generator.generate();
        String fullKey = key.fullKey();
        String[] parts = fullKey.split("_", 3);

        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo("isk");
        assertThat(parts[1]).isEqualTo(key.prefix());
        assertThat(parts[2]).isEqualTo(key.secret());
    }

    @Test
    void shouldUseSecureRandom() {
        when(apiKeyMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        ApiKeyGenerator.GeneratedKey key1 = generator.generate();
        ApiKeyGenerator.GeneratedKey key2 = generator.generate();

        assertThat(key1.secret()).isNotEqualTo(key2.secret());
        assertThat(key1.prefix()).isNotEqualTo(key2.prefix());
    }

    @Test
    void shouldHashSecretNotFullKey() {
        when(apiKeyMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        ApiKeyGenerator.GeneratedKey key = generator.generate();

        assertThat(passwordEncoder.matches(key.secret(), key.keyHash())).isTrue();
        assertThat(passwordEncoder.matches(key.fullKey(), key.keyHash())).isFalse();
    }

    @Test
    void shouldRejectWrongSecret() {
        when(apiKeyMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        ApiKeyGenerator.GeneratedKey key = generator.generate();

        assertThat(passwordEncoder.matches("wrong-secret", key.keyHash())).isFalse();
    }

    @Test
    void shouldHashNotEqualSecret() {
        when(apiKeyMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        ApiKeyGenerator.GeneratedKey key = generator.generate();

        assertThat(key.keyHash()).isNotEqualTo(key.secret());
    }

    @Test
    void shouldRetryOnPrefixCollision() {
        when(apiKeyMapper.selectCount(any(LambdaQueryWrapper.class)))
                .thenReturn(1L)
                .thenReturn(1L)
                .thenReturn(0L);

        ApiKeyGenerator.GeneratedKey key = generator.generate();

        assertThat(key.prefix()).isNotNull();
        assertThat(key.secret()).isNotNull();
    }

    @Test
    void shouldThrowWhenAllPrefixesCollide() {
        when(apiKeyMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        properties.setMaxPrefixRetries(2);

        assertThatThrownBy(() -> generator.generate())
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldGenerateUrlSafeBase64Secret() {
        when(apiKeyMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        ApiKeyGenerator.GeneratedKey key = generator.generate();

        byte[] decoded = Base64.getUrlDecoder().decode(key.secret());
        assertThat(decoded).hasSize(32);
    }
}