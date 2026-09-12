package com.intellidesk.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    private final EmbeddingProperties properties;

    public EmbeddingConfig(EmbeddingProperties properties) {
        this.properties = properties;
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(EmbeddingModel.class)
    @ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "none")
    public EmbeddingModel embeddingModel() {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("EMBEDDING_API_KEY is not set. EmbeddingModel will not be available.");
            throw new IllegalStateException("EMBEDDING_API_KEY must be configured");
        }

        String baseUrl = properties.getBaseUrl();
        log.info("Configuring OpenAiEmbeddingModel with baseUrl={}, model={}, dimension={}",
                baseUrl, properties.getModel(), properties.getDimension());

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .build();

        // AMENDMENT-A: propagate the configured dimension into the OpenAI-compatible
        // embedding request. This is provider-neutral: any OpenAI-compatible endpoint
        // (including local Ollama /v1/embeddings) that honors `dimensions` will return
        // vectors of the requested size. The value is validated to be 1536 by
        // EmbeddingProperties.validate() so the pgvector schema alignment is preserved.
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(properties.getModel())
                .dimensions(properties.getDimension())
                .build();

        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, options);
    }
}