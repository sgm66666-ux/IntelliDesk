package com.intellidesk.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Data
@Component
@ConfigurationProperties(prefix = "intellidesk.minio")
public class MinioProperties {

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;
    private int connectTimeoutSeconds = 5;
    private int readTimeoutSeconds = 30;
    private int writeTimeoutSeconds = 30;

    @PostConstruct
    public void validate() {
        if (!StringUtils.hasText(endpoint)) {
            throw new IllegalStateException("intellidesk.minio.endpoint must not be empty");
        }
        if (!StringUtils.hasText(accessKey)) {
            throw new IllegalStateException("intellidesk.minio.access-key must not be empty");
        }
        if (!StringUtils.hasText(secretKey)) {
            throw new IllegalStateException("intellidesk.minio.secret-key must not be empty");
        }
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException("intellidesk.minio.bucket must not be empty");
        }
        if (connectTimeoutSeconds <= 0 || readTimeoutSeconds <= 0 || writeTimeoutSeconds <= 0) {
            throw new IllegalStateException("intellidesk.minio timeout values must be positive");
        }
    }
}
