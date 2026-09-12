package com.intellidesk.infrastructure.ratelimit;

import com.intellidesk.infrastructure.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final long WARN_INTERVAL_MS = 60_000;

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final DefaultRedisScript<List> luaScript;

    private final AtomicLong lastRedisWarnTime = new AtomicLong(0);

    public RateLimitService(@Autowired(required = false) StringRedisTemplate redisTemplate,
                            RateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;

        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("rate_limit.lua"));
        script.setResultType(List.class);
        this.luaScript = script;
    }

    public RateLimitDecision check(String category, String principal) {
        if (!properties.isEnabled() || redisTemplate == null) {
            return RateLimitDecision.allowed();
        }

        String key = buildKey(principal, category);
        String memberId = UUID.randomUUID().toString();
        int windowSeconds = properties.getWindowSeconds();
        int limit = properties.getLimit(category);

        try {
            List<Long> result = redisTemplate.execute(
                    luaScript,
                    List.of(key),
                    String.valueOf(windowSeconds),
                    String.valueOf(limit),
                    memberId
            );

            if (result == null || result.size() < 6) {
                throw new RuntimeException("Rate limit Lua returned unexpected result");
            }

            long allowed = result.get(0);
            long remaining = result.get(1);
            long retryAfterSeconds = result.get(2);
            long resetEpochSeconds = result.get(3);
            long limitResult = result.get(4);
            long windowResult = result.get(5);

            if (allowed == 1) {
                return RateLimitDecision.allowed(remaining, resetEpochSeconds, limitResult, windowResult);
            } else {
                return RateLimitDecision.rejected(retryAfterSeconds, resetEpochSeconds, limitResult, windowResult);
            }
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            throttleWarn("Redis connection failure during rate limit check: key={}", key, e);
            return RateLimitDecision.allowed();
        } catch (org.springframework.data.redis.RedisSystemException e) {
            if (hasInfrastructureCause(e)) {
                throttleWarn("Redis infrastructure error during rate limit check: key={}", key, e);
                return RateLimitDecision.allowed();
            }
            log.error("Redis programming error during rate limit check: key={}", key, e);
            throw new RuntimeException("Rate limit check failed", e);
        }
    }

    private void throttleWarn(String message, String key, Exception e) {
        long now = System.currentTimeMillis();
        long last = lastRedisWarnTime.get();
        if (now - last >= WARN_INTERVAL_MS && lastRedisWarnTime.compareAndSet(last, now)) {
            log.warn(message, key, e);
        }
    }

    private String buildKey(String principal, String category) {
        return properties.getNamespace() + ":rl:" + principal + ":" + category;
    }

    private boolean hasInfrastructureCause(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof java.net.SocketException
                    || current instanceof java.io.IOException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}