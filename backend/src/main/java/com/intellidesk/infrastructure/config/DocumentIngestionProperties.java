package com.intellidesk.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "intellidesk.document-ingestion")
public class DocumentIngestionProperties {

    private long maxFileSizeBytes = 20 * 1024 * 1024;
    private int maxPdfPages = 500;
    private long maxExtractedCharacters = 5_000_000;
    private int processingLeaseMinutes = 10;
    private int dispatchStaleTimeoutSeconds = 60;
    private int dispatcherIntervalSeconds = 10;
    private int retryDelaySeconds = 30;
    private int maxAttempts = 3;
    private int uploadCleanupGraceSeconds = 120;
    private int uploadAbandonedTimeoutSeconds = 300;
    private boolean recoverySchedulerEnabled = true;

    @PostConstruct
    public void validate() {
        if (maxFileSizeBytes <= 0) {
            throw new IllegalStateException("intellidesk.document-ingestion.max-file-size-bytes must be positive");
        }
        if (maxPdfPages <= 0) {
            throw new IllegalStateException("intellidesk.document-ingestion.max-pdf-pages must be positive");
        }
        if (maxExtractedCharacters <= 0) {
            throw new IllegalStateException("intellidesk.document-ingestion.max-extracted-characters must be positive");
        }
        if (processingLeaseMinutes <= 0) {
            throw new IllegalStateException("intellidesk.document-ingestion.processing-lease-minutes must be positive");
        }
        if (dispatchStaleTimeoutSeconds <= 0) {
            throw new IllegalStateException("intellidesk.document-ingestion.dispatch-stale-timeout-seconds must be positive");
        }
        if (dispatcherIntervalSeconds <= 0) {
            throw new IllegalStateException("intellidesk.document-ingestion.dispatcher-interval-seconds must be positive");
        }
        if (retryDelaySeconds <= 0) {
            throw new IllegalStateException("intellidesk.document-ingestion.retry-delay-seconds must be positive");
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalStateException("intellidesk.document-ingestion.max-attempts must be between 1 and 10");
        }
        if (uploadCleanupGraceSeconds <= 0) {
            throw new IllegalStateException("intellidesk.document-ingestion.upload-cleanup-grace-seconds must be positive");
        }
        if (uploadAbandonedTimeoutSeconds <= 0) {
            throw new IllegalStateException("intellidesk.document-ingestion.upload-abandoned-timeout-seconds must be positive");
        }
    }
}
