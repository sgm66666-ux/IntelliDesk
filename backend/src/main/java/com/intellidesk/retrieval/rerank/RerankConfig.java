package com.intellidesk.retrieval.rerank;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RerankConfig {

    @Bean
    @ConfigurationProperties(prefix = "intellidesk.retrieval.rerank")
    public RerankProperties rerankProperties() {
        return new RerankProperties();
    }

    @Bean
    @ConditionalOnProperty(name = "intellidesk.retrieval.rerank.enabled", havingValue = "true")
    public RerankClient rerankClient(RerankProperties props) {
        return new DashScopeCompatibleRerankClient(
                props.getBaseUrl(),
                props.getApiKey(),
                props.getModel(),
                Duration.ofSeconds(props.getTimeoutSeconds()));
    }

    @Bean
    @ConditionalOnProperty(name = "intellidesk.retrieval.rerank.enabled", havingValue = "true")
    public RerankService rerankService(RerankClient rerankClient) {
        return new RerankService(rerankClient);
    }
}