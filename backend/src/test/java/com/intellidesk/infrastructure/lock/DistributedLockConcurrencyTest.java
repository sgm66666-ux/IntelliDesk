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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestInfrastructureConfig.class, DistributedLockConcurrencyTest.RedisTestConfig.class})
@Testcontainers
@DisplayName("DistributedLockConcurrencyTest - Real Redis Concurrency")
class DistributedLockConcurrencyTest {

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
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DistributedLockProperties properties;

    @BeforeEach
    void setUp() {
        String key = properties.buildKey("document-recovery");
        redisTemplate.delete(key);
    }

    @Test
    @DisplayName("10 threads compete: exactly 1 ACQUIRED, rest CONTENDED")
    void shouldHaveSingleWinnerWithMultipleThreads() throws Exception {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger acquiredCount = new AtomicInteger(0);
        AtomicInteger contendedCount = new AtomicInteger(0);
        List<String> acquiredTokens = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    LockHandle handle = distributedLock.tryAcquire("document-recovery");
                    if (handle.result() == LockResult.ACQUIRED) {
                        acquiredCount.incrementAndGet();
                        synchronized (acquiredTokens) {
                            acquiredTokens.add(handle.ownerToken());
                        }
                        // Hold lock briefly
                        Thread.sleep(500);
                        distributedLock.release("document-recovery", handle.ownerToken());
                    } else if (handle.result() == LockResult.CONTENDED) {
                        contendedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Don't count infra failures as contention
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);

        assertThat(acquiredCount.get()).isEqualTo(1);
        assertThat(contendedCount.get()).isEqualTo(threadCount - 1);
        assertThat(acquiredTokens).hasSize(1);
    }

    @Test
    @DisplayName("sequential: release then acquire works after contention")
    void shouldAllowAcquireAfterRelease() throws Exception {
        LockHandle first = distributedLock.tryAcquire("document-recovery");
        assertThat(first.result()).isEqualTo(LockResult.ACQUIRED);

        // Second thread contends
        LockHandle second = distributedLock.tryAcquire("document-recovery");
        assertThat(second.result()).isEqualTo(LockResult.CONTENDED);

        // Release first
        distributedLock.release("document-recovery", first.ownerToken());

        // Now second can acquire
        LockHandle third = distributedLock.tryAcquire("document-recovery");
        assertThat(third.result()).isEqualTo(LockResult.ACQUIRED);

        distributedLock.release("document-recovery", third.ownerToken());
    }

    @Test
    @DisplayName("two threads compete, one wins and holds, other contends")
    void shouldHaveExactOneWinner() throws Exception {
        CountDownLatch t1Ready = new CountDownLatch(1);
        CountDownLatch t1Done = new CountDownLatch(1);
        CountDownLatch t2Done = new CountDownLatch(1);

        AtomicInteger t1Result = new AtomicInteger(-1);
        AtomicInteger t2Result = new AtomicInteger(-1);

        // Thread 1: acquires lock and holds it
        Thread t1 = new Thread(() -> {
            try {
                LockHandle handle = distributedLock.tryAcquire("document-recovery");
                if (handle.result() == LockResult.ACQUIRED) {
                    t1Result.set(0);
                    t1Ready.countDown();
                    Thread.sleep(2000); // Hold lock
                    distributedLock.release("document-recovery", handle.ownerToken());
                } else {
                    t1Result.set(1);
                }
            } catch (Exception ignored) {
                t1Result.set(2);
            } finally {
                t1Done.countDown();
            }
        });

        // Thread 2: tries to acquire while t1 holds
        Thread t2 = new Thread(() -> {
            try {
                t1Ready.await(5, TimeUnit.SECONDS);
                // Small delay to ensure t1 still holds the lock
                Thread.sleep(200);
                LockHandle handle = distributedLock.tryAcquire("document-recovery");
                if (handle.result() == LockResult.ACQUIRED) {
                    t2Result.set(0);
                } else if (handle.result() == LockResult.CONTENDED) {
                    t2Result.set(1);
                } else {
                    t2Result.set(2);
                }
            } catch (Exception ignored) {
                t2Result.set(3);
            } finally {
                t2Done.countDown();
            }
        });

        t1.start();
        t2.start();
        t1Done.await(15, TimeUnit.SECONDS);
        t2Done.await(15, TimeUnit.SECONDS);

        assertThat(t1Result.get()).isEqualTo(0); // Thread 1 acquired
        assertThat(t2Result.get()).isEqualTo(1); // Thread 2 contended
    }
}