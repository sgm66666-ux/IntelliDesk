package com.intellidesk.chat.context;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "intellidesk.chat.context")
public class ContextProperties {

    /**
     * Token budget for RAG context. Default 4096.
     */
    private int tokenBudget = 4096;

    /**
     * Candidate top-K for retrieval. Default 30.
     */
    private int candidateTopK = 30;

    /**
     * Final top-K after rerank. Default 8.
     */
    private int topK = 8;

    /**
     * Maximum characters per citation content snippet. Default 1000.
     */
    private int citationContentMaxChars = 1000;

    @PostConstruct
    public void validate() {
        if (tokenBudget <= 0) {
            throw new IllegalStateException("intellidesk.chat.context.token-budget must be > 0");
        }
        if (candidateTopK < 1) {
            throw new IllegalStateException("intellidesk.chat.context.candidate-top-k must be >= 1");
        }
        if (topK < 1) {
            throw new IllegalStateException("intellidesk.chat.context.top-k must be >= 1");
        }
        if (candidateTopK < topK) {
            throw new IllegalStateException("intellidesk.chat.context.candidate-top-k must be >= top-k");
        }
        if (citationContentMaxChars < 1) {
            throw new IllegalStateException("intellidesk.chat.context.citation-content-max-chars must be >= 1");
        }
    }
}