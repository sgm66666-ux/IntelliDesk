package com.intellidesk.api_key;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class ApiKeyGenerator {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyGenerator.class);
    private static final String KEY_PREFIX = "isk";

    private final SecureRandom secureRandom = new SecureRandom();
    private final PasswordEncoder passwordEncoder;
    private final ApiKeyProperties properties;
    private final ApiKeyMapper apiKeyMapper;

    public ApiKeyGenerator(PasswordEncoder passwordEncoder,
                           ApiKeyProperties properties,
                           ApiKeyMapper apiKeyMapper) {
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.apiKeyMapper = apiKeyMapper;
    }

    public GeneratedKey generate() {
        int maxRetries = properties.getMaxPrefixRetries();
        for (int i = 0; i < maxRetries; i++) {
            String prefix = generatePrefix();
            String secret = generateSecret();
            String fullKey = KEY_PREFIX + "_" + prefix + "_" + secret;
            String keyHash = passwordEncoder.encode(secret);

            if (prefixAvailable(prefix)) {
                log.debug("API Key generated with prefix={} (attempt {})", prefix, i + 1);
                return new GeneratedKey(prefix, secret, fullKey, keyHash);
            }
            log.debug("Prefix collision: prefix={}, retrying ({}/{})", prefix, i + 1, maxRetries);
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "API Key 前缀生成失败，请重试");
    }

    private static final String PREFIX_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private String generatePrefix() {
        StringBuilder sb = new StringBuilder(properties.getPrefixLength());
        for (int i = 0; i < properties.getPrefixLength(); i++) {
            sb.append(PREFIX_ALPHABET.charAt(secureRandom.nextInt(PREFIX_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String generateSecret() {
        byte[] bytes = new byte[properties.getSecretBytes()];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean prefixAvailable(String prefix) {
        return apiKeyMapper.selectCount(
                new LambdaQueryWrapper<ApiKey>().eq(ApiKey::getKeyPrefix, prefix)) == 0;
    }

    public record GeneratedKey(String prefix, String secret, String fullKey, String keyHash) {
    }
}