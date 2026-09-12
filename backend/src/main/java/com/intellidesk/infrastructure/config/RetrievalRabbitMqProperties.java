package com.intellidesk.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Data
@Component
@ConfigurationProperties(prefix = "intellidesk.retrieval.rabbitmq")
public class RetrievalRabbitMqProperties {

    private String exchange = "intellidesk.retrieval.x";
    private String routingKey = "retrieval.index";
    private String queue = "intellidesk.retrieval.index.q";

    private String retryExchange = "intellidesk.retrieval.dlx";
    private String retryRoutingKey = "retrieval.index.retry";
    private String retryQueue = "intellidesk.retrieval.index.retry.q";
    private int retryTtlMilliseconds = 30000;

    private String deadLetterExchange = "intellidesk.retrieval.dlx";
    private String deadLetterRoutingKey = "retrieval.index.dead";
    private String deadLetterQueue = "intellidesk.retrieval.index.dlq";

    @PostConstruct
    public void validate() {
        if (!StringUtils.hasText(exchange)) {
            throw new IllegalStateException("intellidesk.retrieval.rabbitmq.exchange must not be empty");
        }
        if (!StringUtils.hasText(queue)) {
            throw new IllegalStateException("intellidesk.retrieval.rabbitmq.queue must not be empty");
        }
        if (retryTtlMilliseconds <= 0) {
            throw new IllegalStateException("intellidesk.retrieval.rabbitmq.retry-ttl-milliseconds must be positive");
        }
    }
}