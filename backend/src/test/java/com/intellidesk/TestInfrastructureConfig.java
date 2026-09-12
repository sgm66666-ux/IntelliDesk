package com.intellidesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.infrastructure.config.MinioProperties;
import com.intellidesk.retrieval.VectorRetriever;
import com.intellidesk.retrieval.fusion.HybridRetriever;
import com.intellidesk.retrieval.indexing.RetrievalIndexingService;
import com.intellidesk.retrieval.keyword.KeywordRetriever;
import io.minio.MinioClient;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@TestConfiguration
@Profile("test")
public class TestInfrastructureConfig {

    @Bean
    public MinioClient minioClient(MinioProperties minioProperties) {
        return MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
    }

    @Bean
    @Primary
    public EmbeddingModel mockEmbeddingModel() {
        return Mockito.mock(EmbeddingModel.class);
    }

    @Bean
    @Primary
    public ChatModel mockChatModel() {
        return Mockito.mock(ChatModel.class);
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return Mockito.mock(RedisConnectionFactory.class);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return Mockito.mock(StringRedisTemplate.class);
    }

    @Bean
    public KeywordRetriever mockKeywordRetriever() {
        return Mockito.mock(KeywordRetriever.class);
    }

    @Bean
    public HybridRetriever mockHybridRetriever(VectorRetriever vectorRetriever, KeywordRetriever keywordRetriever) {
        return Mockito.mock(HybridRetriever.class);
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RetrievalIndexingService mockRetrievalIndexingService() {
        return Mockito.mock(RetrievalIndexingService.class);
    }
}
