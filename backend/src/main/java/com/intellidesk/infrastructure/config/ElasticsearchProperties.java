package com.intellidesk.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "intellidesk.elasticsearch")
public class ElasticsearchProperties {

    private String host = "localhost";
    private int port = 9200;
    private String username;
    private String password;
    private int connectionTimeoutSeconds = 5;
    private int readTimeoutSeconds = 30;

    @PostConstruct
    public void validate() {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("intellidesk.elasticsearch.host must not be empty");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalStateException("intellidesk.elasticsearch.port must be between 1 and 65535");
        }
        if (connectionTimeoutSeconds <= 0) {
            throw new IllegalStateException("intellidesk.elasticsearch.connection-timeout-seconds must be positive");
        }
        if (readTimeoutSeconds <= 0) {
            throw new IllegalStateException("intellidesk.elasticsearch.read-timeout-seconds must be positive");
        }
    }
}