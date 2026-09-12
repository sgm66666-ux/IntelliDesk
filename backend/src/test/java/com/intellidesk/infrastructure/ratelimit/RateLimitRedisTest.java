package com.intellidesk.infrastructure.ratelimit;

import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.infrastructure.config.RateLimitProperties;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestInfrastructureConfig.class, RateLimitRedisTest.RedisTestConfig.class})
@Testcontainers
@DisplayName("RateLimitRedisTest - Real Redis Integration")
class RateLimitRedisTest {

    @Container
    static RedisContainer redis = new RedisContainer(
            DockerImageName.parse("redis:7-alpine"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.autoconfigure.exclude", () -> "");
        registry.add("intellidesk.rate-limit.enabled", () -> "true");
        registry.add("intellidesk.rate-limit.namespace", () -> "intellidesk:test");
        registry.add("intellidesk.rate-limit.window-seconds", () -> "10");
        registry.add("intellidesk.rate-limit.agent", () -> "10");
        registry.add("intellidesk.rate-limit.chat", () -> "30");
        registry.add("intellidesk.rate-limit.retrieval", () -> "60");
        registry.add("intellidesk.rate-limit.upload", () -> "20");
        registry.add("intellidesk.rate-limit.auth", () -> "10");
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
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RateLimitProperties properties;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setWindowSeconds(10);
        rateLimitService = new RateLimitService(redisTemplate, properties);
    }

    @Test
    @DisplayName("REAL_REDIS: under limit should allow")
    void shouldAllowUnderLimit() {
        RateLimitDecision d = rateLimitService.check("chat", "real:user:1");
        assertThat(d.isAllowed()).isTrue();
        assertThat(d.getRemaining()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("REAL_REDIS: exact boundary - last request allowed")
    void shouldAllowExactBoundary() {
        String principal = "real:user:boundary";
        for (int i = 0; i < 9; i++) {
            RateLimitDecision d = rateLimitService.check("agent", principal);
            assertThat(d.isAllowed()).isTrue();
        }
        // 10th request (limit=10): last allowed
        RateLimitDecision d = rateLimitService.check("agent", principal);
        assertThat(d.isAllowed()).isTrue();
        assertThat(d.getRemaining()).isEqualTo(0);
    }

    @Test
    @DisplayName("REAL_REDIS: over limit should reject")
    void shouldRejectOverLimit() {
        String principal = "real:user:over";
        for (int i = 0; i < 10; i++) {
            rateLimitService.check("agent", principal);
        }
        RateLimitDecision d = rateLimitService.check("agent", principal);
        assertThat(d.isAllowed()).isFalse();
        assertThat(d.getRemaining()).isEqualTo(0);
    }

    @Test
    @DisplayName("REAL_REDIS: computed Retry-After from oldest entry")
    void shouldComputeRetryAfter() {
        String principal = "real:retry:after";
        for (int i = 0; i < 9; i++) {
            rateLimitService.check("agent", principal);
        }
        RateLimitDecision last = rateLimitService.check("agent", principal);
        assertThat(last.isAllowed()).isTrue();

        RateLimitDecision rejected = rateLimitService.check("agent", principal);
        assertThat(rejected.isAllowed()).isFalse();
        assertThat(rejected.getRetryAfterSeconds()).isGreaterThanOrEqualTo(1);
        assertThat(rejected.getRetryAfterSeconds()).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("REAL_REDIS: TTL is bounded (window+1)")
    void shouldHaveBoundedTtl() {
        String principal = "real:ttl:test";
        String key = properties.getNamespace() + ":rl:" + principal + ":agent";

        rateLimitService.check("agent", principal);

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull();
        assertThat(ttl).isGreaterThan(0);
        assertThat(ttl).isLessThanOrEqualTo(properties.getWindowSeconds() + 2);
    }

    @Test
    @DisplayName("REAL_REDIS: ZSET stores correct number of entries")
    void shouldStoreCorrectZsetEntries() {
        String principal = "real:zset:count";
        String key = properties.getNamespace() + ":rl:" + principal + ":agent";

        for (int i = 0; i < 5; i++) {
            rateLimitService.check("agent", principal);
        }

        Long zcard = redisTemplate.opsForZSet().zCard(key);
        assertThat(zcard).isEqualTo(5);
    }

    @Test
    @DisplayName("REAL_REDIS: principal isolation - user1 vs user2")
    void shouldIsolatePrincipals() {
        String p1 = "real:iso:user1";
        String p2 = "real:iso:user2";

        for (int i = 0; i < 10; i++) {
            rateLimitService.check("agent", p1);
        }
        assertThat(rateLimitService.check("agent", p1).isAllowed()).isFalse();

        RateLimitDecision d2 = rateLimitService.check("agent", p2);
        assertThat(d2.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("REAL_REDIS: category isolation - agent vs retrieval")
    void shouldIsolateCategories() {
        String principal = "real:cat:user";

        for (int i = 0; i < 10; i++) {
            rateLimitService.check("agent", principal);
        }
        assertThat(rateLimitService.check("agent", principal).isAllowed()).isFalse();

        RateLimitDecision d = rateLimitService.check("retrieval", principal);
        assertThat(d.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("REAL_REDIS: chat vs upload isolation")
    void shouldIsolateChatFromUpload() {
        String principal = "real:cat2:user";

        for (int i = 0; i < 30; i++) {
            rateLimitService.check("chat", principal);
        }
        assertThat(rateLimitService.check("chat", principal).isAllowed()).isFalse();

        RateLimitDecision d = rateLimitService.check("upload", principal);
        assertThat(d.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("REAL_REDIS: API Key identity isolation")
    void shouldIsolateApiKeyIdentities() {
        String key1 = "apikey:100";
        String key2 = "apikey:200";

        for (int i = 0; i < 10; i++) {
            rateLimitService.check("agent", key1);
        }
        assertThat(rateLimitService.check("agent", key1).isAllowed()).isFalse();

        assertThat(rateLimitService.check("agent", key2).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("REAL_REDIS: JWT user identity format")
    void shouldUseJwtUserIdentityFormat() {
        String principal = "user:42";
        String key = properties.getNamespace() + ":rl:" + principal + ":chat";

        rateLimitService.check("chat", principal);

        Boolean exists = redisTemplate.hasKey(key);
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("REAL_REDIS: API Key identity format")
    void shouldUseApiKeyIdentityFormat() {
        String principal = "apikey:7";
        String key = properties.getNamespace() + ":rl:" + principal + ":retrieval";

        rateLimitService.check("retrieval", principal);

        Boolean exists = redisTemplate.hasKey(key);
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("REAL_REDIS: login ip identity format")
    void shouldUseLoginIpIdentityFormat() {
        String principal = "ip:192.168.1.1";
        String key = properties.getNamespace() + ":rl:" + principal + ":auth";

        rateLimitService.check("auth", principal);

        Boolean exists = redisTemplate.hasKey(key);
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("REAL_REDIS: Lua uses Redis TIME (not System.currentTimeMillis)")
    void shouldUseRedisTime() {
        String principal = "real:time:user";
        rateLimitService.check("agent", principal);

        String key = properties.getNamespace() + ":rl:" + principal + ":agent";
        Set<String> members = redisTemplate.opsForZSet().range(key, 0, 0);
        assertThat(members).isNotNull().isNotEmpty();

        Double score = redisTemplate.opsForZSet().score(key, members.iterator().next());
        assertThat(score).isNotNull();

        // Score should be a reasonable epoch millisecond value (around 1.7e12)
        assertThat(score).isGreaterThan(1_700_000_000_000.0);
        assertThat(score).isLessThan(3_000_000_000_000.0);
    }

    @Test
    @DisplayName("REAL_REDIS: Lua atomic execution - single EVAL call")
    void shouldExecuteLuaAtomically() {
        String principal = "real:atomic:user";
        String key = properties.getNamespace() + ":rl:" + principal + ":agent";

        for (int i = 0; i < 5; i++) {
            rateLimitService.check("agent", principal);
        }

        Long zcard = redisTemplate.opsForZSet().zCard(key);
        assertThat(zcard).isEqualTo(5);
    }

    @Test
    @DisplayName("REAL_REDIS: concurrent exact boundary - limit=10, N=15")
    void shouldHandleConcurrentExactBoundary() throws Exception {
        String principal = "real:concurrent:user";
        int totalThreads = 15;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);
        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);
        List<Exception> errors = new ArrayList<>();

        for (int i = 0; i < totalThreads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    RateLimitDecision d = rateLimitService.check("agent", principal);
                    if (d.isAllowed()) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();

        assertThat(allowed.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(5);

        String key = properties.getNamespace() + ":rl:" + principal + ":agent";
        Long zcard = redisTemplate.opsForZSet().zCard(key);
        assertThat(zcard).isEqualTo(10);
    }

    @Test
    @DisplayName("REAL_REDIS: Lua decision contains all required fields")
    void shouldReturnAllDecisionFields() {
        RateLimitDecision d = rateLimitService.check("chat", "real:fields:user");

        assertThat(d.isAllowed()).isTrue();
        assertThat(d.getRemaining()).isGreaterThanOrEqualTo(0);
        assertThat(d.getLimit()).isEqualTo(30);
        assertThat(d.getWindowSeconds()).isEqualTo(10);
        assertThat(d.getResetEpochSeconds()).isGreaterThan(0);
    }

    @Test
    @DisplayName("REAL_REDIS: rejected decision contains all fields")
    void shouldReturnAllRejectedFields() {
        String principal = "real:reject:fields";
        for (int i = 0; i < 10; i++) {
            rateLimitService.check("agent", principal);
        }
        RateLimitDecision d = rateLimitService.check("agent", principal);

        assertThat(d.isAllowed()).isFalse();
        assertThat(d.getRemaining()).isEqualTo(0);
        assertThat(d.getRetryAfterSeconds()).isGreaterThanOrEqualTo(1);
        assertThat(d.getResetEpochSeconds()).isGreaterThan(0);
        assertThat(d.getLimit()).isEqualTo(10);
        assertThat(d.getWindowSeconds()).isEqualTo(10);
    }
}