package com.intellidesk.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Data
@Component
@ConfigurationProperties(prefix = "intellidesk.rabbitmq")
public class RabbitMqProperties {

    private String documentExchange = "intellidesk.document.x";
    private String documentRoutingKey = "document.process";
    private String documentQueue = "intellidesk.document.process.q";

    private String retryExchange = "intellidesk.document.dlx";
    private String retryRoutingKey = "document.process.retry";
    private String retryQueue = "intellidesk.document.process.retry.q";
    private int retryTtlMilliseconds = 30000;

    private String deadLetterExchange = "intellidesk.document.dlx";
    private String deadLetterRoutingKey = "document.process.dead";
    private String deadLetterQueue = "intellidesk.document.process.dlq";

    @PostConstruct
    public void validate() {
        if (!StringUtils.hasText(documentExchange)) {
            throw new IllegalStateException("intellidesk.rabbitmq.document-exchange must not be empty");
        }
        if (!StringUtils.hasText(documentQueue)) {
            throw new IllegalStateException("intellidesk.rabbitmq.document-queue must not be empty");
        }
        if (retryTtlMilliseconds <= 0) {
            throw new IllegalStateException("intellidesk.rabbitmq.retry-ttl-milliseconds must be positive");
        }
    }
}
