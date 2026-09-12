package com.intellidesk.retrieval.indexing;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.retrieval.keyword.ElasticsearchChunkIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class RetrievalCleanupService {

    private final RetrievalCleanupTaskMapper cleanupTaskMapper;
    private final ElasticsearchChunkIndex elasticsearchChunkIndex;
    private final RetrievalProperties retrievalProperties;

    private static final int BATCH_SIZE = 20;

    public RetrievalCleanupService(RetrievalCleanupTaskMapper cleanupTaskMapper,
                                    @Nullable ElasticsearchChunkIndex elasticsearchChunkIndex,
                                    RetrievalProperties retrievalProperties) {
        this.cleanupTaskMapper = cleanupTaskMapper;
        this.elasticsearchChunkIndex = elasticsearchChunkIndex;
        this.retrievalProperties = retrievalProperties;
    }

    public void createCleanupTask(Long documentId, Long workspaceId, Long kbId, String esIndexName) {
        LocalDateTime notBefore = LocalDateTime.now()
                .plusSeconds(retrievalProperties.getProcessingLeaseSeconds() + 60);

        RetrievalCleanupTask task = new RetrievalCleanupTask();
        task.setDocumentId(documentId);
        task.setWorkspaceId(workspaceId);
        task.setKnowledgeBaseId(kbId);
        task.setEsIndexName(esIndexName);
        task.setStatus("PENDING");
        task.setNotBefore(notBefore);
        task.setAttemptCount(0);
        task.setMaxAttempts(retrievalProperties.getMaxAttempts());

        cleanupTaskMapper.insert(task);
        log.info("Created cleanup task {} for document {} (notBefore={})", task.getId(), documentId, notBefore);
    }

    @Scheduled(fixedDelayString = "${intellidesk.retrieval.cleanup-interval-ms:60000}")
    public void processCleanup() {
        List<RetrievalCleanupTask> pendingTasks = cleanupTaskMapper.selectList(
                Wrappers.<RetrievalCleanupTask>query()
                        .eq("status", "PENDING")
                        .le("not_before", LocalDateTime.now())
                        .orderByAsc("not_before")
                        .last("LIMIT " + BATCH_SIZE));

        for (RetrievalCleanupTask task : pendingTasks) {
            processTask(task);
        }

        // Also process RETRY_WAIT tasks
        List<RetrievalCleanupTask> retryTasks = cleanupTaskMapper.selectList(
                Wrappers.<RetrievalCleanupTask>query()
                        .eq("status", "RETRY_WAIT")
                        .and(w -> w.isNull("next_retry_at").or().le("next_retry_at", LocalDateTime.now()))
                        .orderByAsc("next_retry_at")
                        .last("LIMIT " + BATCH_SIZE));

        for (RetrievalCleanupTask task : retryTasks) {
            processTask(task);
        }
    }

    private void processTask(RetrievalCleanupTask task) {
        if (elasticsearchChunkIndex == null) {
            log.warn("ElasticsearchChunkIndex not available, skipping cleanup task {}", task.getId());
            return;
        }
        try {
            elasticsearchChunkIndex.deleteByDocumentId(task.getEsIndexName(), task.getDocumentId());

            // Mark SUCCEEDED
            cleanupTaskMapper.update(null,
                    new UpdateWrapper<RetrievalCleanupTask>()
                            .eq("id", task.getId())
                            .set("status", "SUCCEEDED")
                            .set("lease_until", null));

            log.info("Cleanup task {} succeeded for document {} in ES index {}",
                    task.getId(), task.getDocumentId(), task.getEsIndexName());
        } catch (Exception e) {
            log.error("Cleanup task {} failed for document {}: {}",
                    task.getId(), task.getDocumentId(), e.getMessage());

            int newAttempt = task.getAttemptCount() + 1;
            if (newAttempt >= task.getMaxAttempts()) {
                cleanupTaskMapper.update(null,
                        new UpdateWrapper<RetrievalCleanupTask>()
                                .eq("id", task.getId())
                                .set("status", "DEAD")
                                .set("attempt_count", newAttempt)
                                .set("last_error_code", "CLEANUP_FAILED")
                                .set("last_error_message", truncate(e.getMessage(), 512))
                                .set("lease_until", null));
                log.warn("Cleanup task {} -> DEAD after {} attempts", task.getId(), newAttempt);
            } else {
                LocalDateTime nextRetryAt = LocalDateTime.now()
                        .plusSeconds(retrievalProperties.getRetryDelaySeconds());
                cleanupTaskMapper.update(null,
                        new UpdateWrapper<RetrievalCleanupTask>()
                                .eq("id", task.getId())
                                .set("status", "RETRY_WAIT")
                                .set("attempt_count", newAttempt)
                                .set("next_retry_at", nextRetryAt)
                                .set("last_error_code", "CLEANUP_FAILED")
                                .set("last_error_message", truncate(e.getMessage(), 512)));
                log.warn("Cleanup task {} -> RETRY_WAIT (attempt {}/{})", task.getId(), newAttempt, task.getMaxAttempts());
            }
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}