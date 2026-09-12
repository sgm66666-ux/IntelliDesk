package com.intellidesk.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "intellidesk.retrieval")
public class RetrievalProperties {

    private int topK = 10;
    private int candidateTopK = 50;
    private int hnswEfSearch = 100;
    private boolean retrievalWorkerEnabled = false;
    private long dispatcherIntervalMs = 5000;
    private long recoveryIntervalMs = 30000;
    private long cleanupIntervalMs = 60000;
    private int processingLeaseSeconds = 300;
    private int retryDelaySeconds = 30;
    private int maxAttempts = 3;
    private String esIndexName = "intellidesk-chunks-v1";

    @PostConstruct
    public void validate() {
        if (topK < 1 || topK > 100) {
            throw new IllegalStateException("intellidesk.retrieval.top-k must be between 1 and 100");
        }
        if (candidateTopK < topK || candidateTopK > 200) {
            throw new IllegalStateException("intellidesk.retrieval.candidate-top-k must be between top-k and 200");
        }
        if (hnswEfSearch < 1 || hnswEfSearch > 1000) {
            throw new IllegalStateException("intellidesk.retrieval.hnsw-ef-search must be between 1 and 1000");
        }
        if (processingLeaseSeconds < 10 || processingLeaseSeconds > 3600) {
            throw new IllegalStateException("intellidesk.retrieval.processing-lease-seconds must be between 10 and 3600");
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalStateException("intellidesk.retrieval.max-attempts must be between 1 and 10");
        }
    }
}