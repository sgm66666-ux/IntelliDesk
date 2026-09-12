package com.intellidesk.infrastructure.lock;

import com.intellidesk.infrastructure.config.DistributedLockProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis distributed lock using SET NX PX for acquisition and Lua compare-and-delete for safe release.
 * <p>
 * Acquisition uses {@code SET key ownerToken NX PX ttlMs} — atomic, single round-trip.
 * Release uses a Lua script that compares the owner token before deleting, preventing wrong-owner release.
 * <p>
 * Failure classification:
 * <ul>
 *   <li>Redis connection/timeout/infrastructure failure → {@link LockResult#INFRA_FAILURE}</li>
 *   <li>Key already exists → {@link LockResult#CONTENDED} (normal)</li>
 *   <li>NPE / programming error → exception propagates (not caught)</li>
 * </ul>
 */
@Component
public class DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(DistributedLock.class);
    private static final long WARN_INTERVAL_MS = 60_000;

    private final StringRedisTemplate redisTemplate;
    private final DistributedLockProperties properties;
    private final DefaultRedisScript<Long> releaseScript;

    private final AtomicLong lastRedisWarnTime = new AtomicLong(0);

    public DistributedLock(@Autowired(required = false) StringRedisTemplate redisTemplate,
                           DistributedLockProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("release_lock.lua"));
        script.setResultType(Long.class);
        this.releaseScript = script;
    }

    /**
     * Try to acquire a distributed lock for the given resource.
     *
     * @param resource the lock resource name (e.g. "document-recovery")
     * @return LockHandle with result and ownerToken (if ACQUIRED)
     */
    public LockHandle tryAcquire(String resource) {
        if (redisTemplate == null) {
            return LockHandle.infraFailure();
        }

        String key = properties.buildKey(resource);
        String ownerToken = UUID.randomUUID().toString();
        int leaseMs = properties.getLeaseMs();

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, ownerToken, java.time.Duration.ofMillis(leaseMs));

            if (Boolean.TRUE.equals(acquired)) {
                return LockHandle.acquired(ownerToken);
            } else {
                return LockHandle.contended();
            }
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            throttleWarn("Redis connection failure during lock acquisition: resource={}", resource, e);
            return LockHandle.infraFailure();
        } catch (org.springframework.data.redis.RedisSystemException e) {
            if (hasInfrastructureCause(e)) {
                throttleWarn("Redis infrastructure error during lock acquisition: resource={}", resource, e);
                return LockHandle.infraFailure();
            }
            log.error("Redis programming error during lock acquisition: resource={}", resource, e);
            throw new RuntimeException("Lock acquisition failed", e);
        }
    }

    /**
     * Release a distributed lock. Only the owner can release.
     *
     * @param resource   the lock resource name
     * @param ownerToken the token from the acquisition
     * @return true if released, false if not owner or key already gone
     */
    public boolean release(String resource, String ownerToken) {
        if (redisTemplate == null || ownerToken == null) {
            return false;
        }

        String key = properties.buildKey(resource);

        try {
            Long result = redisTemplate.execute(releaseScript, List.of(key), ownerToken);
            return result != null && result == 1L;
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            throttleWarn("Redis connection failure during lock release: resource={}", resource, e);
            return false;
        } catch (org.springframework.data.redis.RedisSystemException e) {
            if (hasInfrastructureCause(e)) {
                throttleWarn("Redis infrastructure error during lock release: resource={}", resource, e);
                return false;
            }
            log.error("Redis programming error during lock release: resource={}", resource, e);
            throw new RuntimeException("Lock release failed", e);
        }
    }

    private void throttleWarn(String message, String resource, Exception e) {
        long now = System.currentTimeMillis();
        long last = lastRedisWarnTime.get();
        if (now - last >= WARN_INTERVAL_MS && lastRedisWarnTime.compareAndSet(last, now)) {
            log.warn(message, resource, e);
        }
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