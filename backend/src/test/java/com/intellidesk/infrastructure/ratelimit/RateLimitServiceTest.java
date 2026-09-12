package com.intellidesk.infrastructure.ratelimit;

import com.intellidesk.infrastructure.config.RateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitService Unit Tests")
class RateLimitServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RateLimitProperties properties;
    private RateLimitService service;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setNamespace("intellidesk:test");
        properties.setWindowSeconds(60);
        properties.setAgent(10);
        properties.setChat(30);
        properties.setRetrieval(60);
        properties.setUpload(20);
        properties.setAuth(10);
        service = new RateLimitService(redisTemplate, properties);
    }

    @Test
    @DisplayName("under limit: should allow with correct remaining")
    void shouldAllowUnderLimit() {
        List<Long> luaResult = List.of(1L, 8L, 0L, 1000000L, 10L, 60L);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(luaResult);

        RateLimitDecision decision = service.check("chat", "user:42");

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.getRemaining()).isEqualTo(8);
        assertThat(decision.getLimit()).isEqualTo(10);
        assertThat(decision.getWindowSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("exact boundary: last allowed request")
    void shouldAllowExactBoundary() {
        List<Long> luaResult = List.of(1L, 0L, 0L, 1000000L, 10L, 60L);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(luaResult);

        RateLimitDecision decision = service.check("chat", "user:42");

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.getRemaining()).isEqualTo(0);
    }

    @Test
    @DisplayName("over limit: should reject with retryAfter")
    void shouldRejectOverLimit() {
        List<Long> luaResult = List.of(0L, 0L, 15L, 1000000L, 10L, 60L);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(luaResult);

        RateLimitDecision decision = service.check("chat", "user:42");

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getRetryAfterSeconds()).isEqualTo(15);
        assertThat(decision.getRemaining()).isEqualTo(0);
    }

    @Test
    @DisplayName("retryAfter: at least 1 second")
    void shouldHaveMinRetryAfterOfOneSecond() {
        List<Long> luaResult = List.of(0L, 0L, 1L, 1000000L, 10L, 60L);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(luaResult);

        RateLimitDecision decision = service.check("agent", "user:42");

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getRetryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("disabled: should always allow")
    void shouldAllowWhenDisabled() {
        properties.setEnabled(false);

        RateLimitDecision decision = service.check("chat", "user:42");

        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("null redisTemplate: should allow (fail-open)")
    void shouldAllowWhenRedisTemplateNull() {
        RateLimitService serviceWithoutRedis = new RateLimitService(null, properties);

        RateLimitDecision decision = serviceWithoutRedis.check("chat", "user:42");

        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("Lua null result: should throw exception")
    void shouldThrowOnNullLuaResult() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(null);

        assertThatThrownBy(() -> service.check("chat", "user:42"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Rate limit Lua returned unexpected result");
    }

    @Test
    @DisplayName("Lua short result: should throw exception")
    void shouldThrowOnShortLuaResult() {
        List<Long> shortResult = List.of(1L, 8L);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(shortResult);

        assertThatThrownBy(() -> service.check("chat", "user:42"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Rate limit Lua returned unexpected result");
    }

    @Test
    @DisplayName("RedisConnectionFailureException: should fail-open")
    void shouldFailOpenOnRedisConnectionFailure() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        RateLimitDecision decision = service.check("chat", "user:42");

        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("RedisSystemException with SocketException cause: should fail-open")
    void shouldFailOpenOnSocketException() {
        RedisSystemException ex = new RedisSystemException("socket error",
                new RedisConnectionFailureException("inner", new SocketException("broken pipe")));
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(ex);

        RateLimitDecision decision = service.check("chat", "user:42");

        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("RedisSystemException with IOException cause: should fail-open")
    void shouldFailOpenOnIOException() {
        RedisSystemException ex = new RedisSystemException("io error",
                new RedisConnectionFailureException("inner", new IOException("connection reset")));
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(ex);

        RateLimitDecision decision = service.check("chat", "user:42");

        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("RedisSystemException with TimeoutException cause: should fail-open")
    void shouldFailOpenOnTimeoutException() {
        RedisSystemException ex = new RedisSystemException("timeout",
                new RedisConnectionFailureException("inner", new TimeoutException("read timed out")));
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(ex);

        RateLimitDecision decision = service.check("chat", "user:42");

        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("non-Redis programming error: should NOT fail-open")
    void shouldNotFailOpenOnProgrammingError() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new NullPointerException("unexpected null"));

        assertThatThrownBy(() -> service.check("chat", "user:42"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("unexpected null");
    }

    @Test
    @DisplayName("RedisSystemException with non-infra cause: should NOT fail-open")
    void shouldNotFailOpenOnNonInfraRedisSystemException() {
        RedisSystemException ex = new RedisSystemException("script error",
                new IllegalStateException("bad script"));
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(ex);

        assertThatThrownBy(() -> service.check("chat", "user:42"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Rate limit check failed");
    }

    @Test
    @DisplayName("category isolation: agent vs retrieval use different keys")
    void shouldIsolateCategories() {
        List<Long> agentResult = List.of(1L, 8L, 0L, 1000000L, 10L, 60L);
        List<Long> retrievalResult = List.of(1L, 58L, 0L, 1000000L, 60L, 60L);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(agentResult, retrievalResult);

        RateLimitDecision agentDecision = service.check("agent", "user:42");
        RateLimitDecision retrievalDecision = service.check("retrieval", "user:42");

        assertThat(agentDecision.getLimit()).isEqualTo(10);
        assertThat(retrievalDecision.getLimit()).isEqualTo(60);
        assertThat(agentDecision.getRemaining()).isEqualTo(8);
        assertThat(retrievalDecision.getRemaining()).isEqualTo(58);
    }

    @Test
    @DisplayName("principal isolation: user1 vs user2 use different keys")
    void shouldIsolatePrincipals() {
        List<Long> result1 = List.of(1L, 5L, 0L, 1000000L, 10L, 60L);
        List<Long> result2 = List.of(1L, 9L, 0L, 1000000L, 10L, 60L);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(result1, result2);

        RateLimitDecision d1 = service.check("agent", "user:1");
        RateLimitDecision d2 = service.check("agent", "user:2");

        assertThat(d1.getRemaining()).isEqualTo(5);
        assertThat(d2.getRemaining()).isEqualTo(9);
    }

    @Test
    @DisplayName("unknown category: should throw IllegalArgumentException")
    void shouldThrowOnUnknownCategory() {
        assertThatThrownBy(() -> service.check("unknown", "user:42"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown rate limit category");
    }
}