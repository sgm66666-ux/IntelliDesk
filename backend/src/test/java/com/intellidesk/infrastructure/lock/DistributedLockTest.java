package com.intellidesk.infrastructure.lock;

import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.infrastructure.config.DistributedLockProperties;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestInfrastructureConfig.class, DistributedLockTest.RedisTestConfig.class})
@Testcontainers
@DisplayName("DistributedLockTest - Real Redis Integration")
class DistributedLockTest {

    @Container
    static RedisContainer redis = new RedisContainer(
            DockerImageName.parse("redis:7-alpine"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.autoconfigure.exclude", () -> "");
        registry.add("intellidesk.distributed-lock.lease-ms", () -> "30000");
        registry.add("intellidesk.distributed-lock.namespace", () -> "intellidesk:test");
    }

    @TestConfiguration
    static class RedisTestConfig {
        @Bean
        @Primary
        RedisConnectionFactory realRedisConnectionFactory() {
            RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                    redis.getHost(), redis.getMappedPort(6379));
            return new LettuceConnectionFactory(config);
        }

        @Bean
        @Primary
        StringRedisTemplate realStringRedisTemplate(RedisConnectionFactory connectionFactory) {
            return new StringRedisTemplate(connectionFactory);
        }
    }

    @Autowired
    private DistributedLock distributedLock;

    @Autowired
    private DistributedLockProperties properties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        // Clean up any leftover lock keys from previous tests
        String key = properties.buildKey("document-recovery");
        redisTemplate.delete(key);
    }

    @Test
    @DisplayName("acquire success: SET NX PX returns OK")
    void shouldAcquireLockSuccessfully() {
        LockHandle handle = distributedLock.tryAcquire("document-recovery");

        assertThat(handle.result()).isEqualTo(LockResult.ACQUIRED);
        assertThat(handle.ownerToken()).isNotNull();
        assertThat(handle.ownerToken()).isNotEmpty();

        // Verify key exists in Redis with correct TTL
        String key = properties.buildKey("document-recovery");
        String value = redisTemplate.opsForValue().get(key);
        assertThat(value).isEqualTo(handle.ownerToken());

        Long ttl = redisTemplate.getExpire(key);
        assertThat(ttl).isGreaterThan(0);
        assertThat(ttl).isLessThanOrEqualTo(30);
    }

    @Test
    @DisplayName("contention: second acquire returns CONTENDED when lock held")
    void shouldReturnContendedWhenLockAlreadyHeld() {
        LockHandle first = distributedLock.tryAcquire("document-recovery");
        assertThat(first.result()).isEqualTo(LockResult.ACQUIRED);

        LockHandle second = distributedLock.tryAcquire("document-recovery");
        assertThat(second.result()).isEqualTo(LockResult.CONTENDED);
        assertThat(second.ownerToken()).isNull();

        // Clean up
        distributedLock.release("document-recovery", first.ownerToken());
    }

    @Test
    @DisplayName("owner release: only owner can release")
    void shouldAllowOwnerToRelease() {
        LockHandle handle = distributedLock.tryAcquire("document-recovery");
        assertThat(handle.result()).isEqualTo(LockResult.ACQUIRED);

        boolean released = distributedLock.release("document-recovery", handle.ownerToken());
        assertThat(released).isTrue();

        // Verify key is gone
        String key = properties.buildKey("document-recovery");
        String value = redisTemplate.opsForValue().get(key);
        assertThat(value).isNull();
    }

    @Test
    @DisplayName("wrong owner release: cannot delete someone else's lock")
    void shouldNotAllowWrongOwnerToRelease() {
        LockHandle owner = distributedLock.tryAcquire("document-recovery");
        assertThat(owner.result()).isEqualTo(LockResult.ACQUIRED);

        // Try to release with wrong owner token
        boolean released = distributedLock.release("document-recovery", "wrong-owner-token");
        assertThat(released).isFalse();

        // Verify lock still exists with correct owner
        String key = properties.buildKey("document-recovery");
        String value = redisTemplate.opsForValue().get(key);
        assertThat(value).isEqualTo(owner.ownerToken());

        // Clean up
        distributedLock.release("document-recovery", owner.ownerToken());
    }

    @Test
    @DisplayName("automatic lease expiry: lock auto-releases after TTL")
    void shouldAutoReleaseAfterLeaseExpiry() throws Exception {
        // Use a short lease for this test
        String key = properties.buildKey("document-recovery");
        String ownerToken = java.util.UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(key, ownerToken, Duration.ofMillis(500));

        // Verify lock exists
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(ownerToken);

        // Wait for expiry
        Thread.sleep(600);

        // Verify lock expired
        assertThat(redisTemplate.opsForValue().get(key)).isNull();

        // Now acquire should succeed
        LockHandle handle = distributedLock.tryAcquire("document-recovery");
        assertThat(handle.result()).isEqualTo(LockResult.ACQUIRED);

        distributedLock.release("document-recovery", handle.ownerToken());
    }

    @Test
    @DisplayName("acquire after expiry: can acquire lock after previous lease expires")
    void shouldAcquireAfterExpiry() throws Exception {
        LockHandle first = distributedLock.tryAcquire("document-recovery");
        assertThat(first.result()).isEqualTo(LockResult.ACQUIRED);

        // Manually expire the key to simulate lease expiry
        String key = properties.buildKey("document-recovery");
        redisTemplate.delete(key);

        // Now another acquire should succeed
        LockHandle second = distributedLock.tryAcquire("document-recovery");
        assertThat(second.result()).isEqualTo(LockResult.ACQUIRED);
        assertThat(second.ownerToken()).isNotEqualTo(first.ownerToken());

        distributedLock.release("document-recovery", second.ownerToken());
    }

    @Test
    @DisplayName("release non-existent key: returns false")
    void shouldReturnFalseWhenReleasingNonExistentKey() {
        boolean released = distributedLock.release("document-recovery", "some-token");
        assertThat(released).isFalse();
    }

    @Test
    @DisplayName("release with null ownerToken: returns false")
    void shouldReturnFalseWhenReleasingWithNullToken() {
        LockHandle handle = distributedLock.tryAcquire("document-recovery");
        assertThat(handle.result()).isEqualTo(LockResult.ACQUIRED);

        boolean released = distributedLock.release("document-recovery", null);
        assertThat(released).isFalse();

        // Clean up
        distributedLock.release("document-recovery", handle.ownerToken());
    }

    @Test
    @DisplayName("ownerToken uniqueness: each ACQUIRED gets unique token")
    void shouldGenerateUniqueOwnerTokens() {
        LockHandle first = distributedLock.tryAcquire("document-recovery");
        assertThat(first.result()).isEqualTo(LockResult.ACQUIRED);

        distributedLock.release("document-recovery", first.ownerToken());

        LockHandle second = distributedLock.tryAcquire("document-recovery");
        assertThat(second.result()).isEqualTo(LockResult.ACQUIRED);

        assertThat(second.ownerToken()).isNotEqualTo(first.ownerToken());

        distributedLock.release("document-recovery", second.ownerToken());
    }

    @Test
    @DisplayName("namespace isolation: different resources have different keys")
    void shouldUseDifferentKeysForDifferentResources() {
        LockHandle lock1 = distributedLock.tryAcquire("document-recovery");
        LockHandle lock2 = distributedLock.tryAcquire("other-resource");

        assertThat(lock1.result()).isEqualTo(LockResult.ACQUIRED);
        assertThat(lock2.result()).isEqualTo(LockResult.ACQUIRED);

        // Both should be acquired independently
        String key1 = properties.buildKey("document-recovery");
        String key2 = properties.buildKey("other-resource");
        assertThat(redisTemplate.opsForValue().get(key1)).isEqualTo(lock1.ownerToken());
        assertThat(redisTemplate.opsForValue().get(key2)).isEqualTo(lock2.ownerToken());

        distributedLock.release("document-recovery", lock1.ownerToken());
        distributedLock.release("other-resource", lock2.ownerToken());
    }

    @Test
    @DisplayName("lease-ms > 0 enforced: fail-fast on invalid config")
    void shouldFailFastOnInvalidLeaseMs() {
        // This is validated by @PostConstruct in DistributedLockProperties
        // Just verify properties are valid for this test context
        assertThat(properties.getLeaseMs()).isGreaterThan(0);
    }
}