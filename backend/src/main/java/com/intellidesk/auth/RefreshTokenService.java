package com.intellidesk.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class RefreshTokenService {

    private final StringRedisTemplate redisTemplate;
    private final long refreshTokenExpiration;

    private static final String REFRESH_TOKEN_PREFIX = "refresh_token:";

    private static final String CONSUME_SCRIPT = """
            local key = KEYS[1]
            local value = redis.call('GET', key)
            if value then
                redis.call('DEL', key)
            end
            return value
            """;

    public RefreshTokenService(StringRedisTemplate redisTemplate,
                               @Value("${jwt.refresh-token-expiration}") long refreshTokenExpiration) {
        this.redisTemplate = redisTemplate;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    public String createRefreshToken(Long userId) {
        String tokenId = UUID.randomUUID().toString();
        String key = REFRESH_TOKEN_PREFIX + tokenId;
        redisTemplate.opsForValue().set(key, userId.toString(),
                refreshTokenExpiration, TimeUnit.MILLISECONDS);
        return tokenId;
    }

    public Long consumeRefreshToken(String tokenId) {
        String key = REFRESH_TOKEN_PREFIX + tokenId;
        DefaultRedisScript<String> script = new DefaultRedisScript<>(CONSUME_SCRIPT, String.class);
        String userId = redisTemplate.execute(script, Collections.singletonList(key));
        if (userId == null) {
            return null;
        }
        return Long.valueOf(userId);
    }

    public void revokeRefreshToken(String tokenId) {
        String key = REFRESH_TOKEN_PREFIX + tokenId;
        redisTemplate.delete(key);
    }
}