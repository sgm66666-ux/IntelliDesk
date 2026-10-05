package com.intellidesk.knowledge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class KnowledgeBaseRedisIntegrationTest {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @Test void actualRedisRoundTripTtlAndEviction() {
        var connection=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379)); connection.afterPropertiesSet();
        try {
            var redis=new StringRedisTemplate(connection); var cache=new KnowledgeBaseMetadataCache(redis,new ObjectMapper().findAndRegisterModules());
            var kb=new KnowledgeBase(); kb.setId(2L); kb.setWorkspaceId(1L); kb.setName("Synthetic metadata");
            assertThat(cache.get(1L,2L)).isNull(); cache.put(kb); assertThat(cache.get(1L,2L)).isEqualTo(kb);
            assertThat(redis.getExpire(cache.key(1L,2L))).isBetween(299L,360L);
            cache.invalidateAfterCommit(1L,2L); assertThat(cache.get(1L,2L)).isNull();
        } finally { connection.destroy(); }
    }
}
