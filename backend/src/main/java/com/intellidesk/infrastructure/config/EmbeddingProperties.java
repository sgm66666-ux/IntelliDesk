package com.intellidesk.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "intellidesk.embedding")
public class EmbeddingProperties {

    private String apiKey;
    private String baseUrl = "https://api.openai.com";
    private String model;
    private int dimension = 1536;
    private int batchSize = 20;

    @PostConstruct
    public void validate() {
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("intellidesk.embedding.model must be configured");
        }
        if (dimension != 1536) {
            throw new IllegalStateException("intellidesk.embedding.dimension must be 1536");
        }
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalStateException("intellidesk.embedding.batch-size must be between 1 and 100");
        }
    }
}