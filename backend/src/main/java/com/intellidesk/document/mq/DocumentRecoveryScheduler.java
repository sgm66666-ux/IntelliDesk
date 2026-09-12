package com.intellidesk.document.mq;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.intellidesk.document.DocumentIndexTask;
import com.intellidesk.document.DocumentIndexTaskMapper;
import com.intellidesk.document.DocumentMapper;
import com.intellidesk.document.KnowledgeDocument;
import com.intellidesk.document.model.DocumentStatus;
import com.intellidesk.document.model.DocumentTaskStatus;
import com.intellidesk.infrastructure.config.DocumentIngestionProperties;
import com.intellidesk.infrastructure.lock.DistributedLock;
import com.intellidesk.infrastructure.lock.LockHandle;
import com.intellidesk.infrastructure.lock.LockResult;
import com.intellidesk.infrastructure.storage.ObjectStorageService;
import com.intellidesk.infrastructure.storage.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@Profile("!test")
@ConditionalOnProperty(name = "intellidesk.document-ingestion.recovery-scheduler-enabled", havingValue = "true",
        matchIfMissing = true)
public class DocumentRecoveryScheduler {

    private final DocumentIndexTaskMapper taskMapper;
    private final DocumentMapper documentMapper;
    private final ObjectStorageService objectStorageService;
    private final DocumentTaskPublisher publisher;
    private final DocumentIngestionProperties ingestionProperties;
    private final TransactionTemplate transactionTemplate;
    private final DistributedLock distributedLock;

    private static final int BATCH_SIZE = 20;
    private static final int RECOVERY_INTERVAL_SECONDS = 15;

    public DocumentRecoveryScheduler(DocumentIndexTaskMapper taskMapper,
                                      DocumentMapper documentMapper,
                                      ObjectStorageService objectStorageService,
                                      DocumentTaskPublisher publisher,
                                      DocumentIngestionProperties ingestionProperties,
                                      PlatformTransactionManager transactionManager,
                                      DistributedLock distributedLock) {
        this.taskMapper = taskMapper;
        this.documentMapper = documentMapper;
        this.objectStorageService = objectStorageService;
        this.publisher = publisher;
        this.ingestionProperties = ingestionProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.distributedLock = distributedLock;
    }

    @Scheduled(fixedDelay = RECOVERY_INTERVAL_SECONDS * 1000)
    public void recover() {
        LockHandle handle = distributedLock.tryAcquire("document-recovery");

        switch (handle.result()) {
            case ACQUIRED -> {
                try {
                    log.debug("Acquired distributed lock for document recovery");
                    executeRecovery();
                } finally {
                    distributedLock.release("document-recovery", handle.ownerToken());
                }
            }
            case CONTENDED -> {
                log.debug("Document recovery skipped — another instance holds the lock");
            }
            case INFRA_FAILURE -> {
                log.warn("Redis unavailable during lock acquisition for document recovery, falling back to DB-CAS path");
                executeRecovery();
            }
        }
    }

    private void executeRecovery() {
        recoverStaleProcessing();
        recoverStaleUploading();
        cleanupDeletingDocuments();
        dispatchDeadToDlq();
    }

    /**
     * Recover stale PROCESSING tasks whose lease has expired.
     * Uses attempt_count as fence: only recovers the same attempt that was read,
     * preventing stale worker recovery races.
     */
    private void recoverStaleProcessing() {
        List<DocumentIndexTask> staleTasks = taskMapper.selectList(
                Wrappers.<DocumentIndexTask>query()
                        .eq("status", DocumentTaskStatus.PROCESSING.getValue())
                        .lt("lease_until", LocalDateTime.now())
                        .orderByAsc("lease_until")
                        .last("LIMIT " + BATCH_SIZE));

        for (DocumentIndexTask task : staleTasks) {
            transactionTemplate.execute(status -> {
                LocalDateTime now = LocalDateTime.now();
                int fenceAttempt = task.getAttemptCount();

                if (task.getAttemptCount() < task.getMaxAttempts()) {
                    // Retryable: CAS PROCESSING -> RETRY_WAIT with attempt_count fence
                    LocalDateTime nextRetryAt = now.plusSeconds(ingestionProperties.getRetryDelaySeconds());
                    int taskUpdated = taskMapper.update(null,
                            new UpdateWrapper<DocumentIndexTask>()
                                    .eq("id", task.getId())
                                    .eq("status", DocumentTaskStatus.PROCESSING.getValue())
                                    .eq("attempt_count", fenceAttempt)
                                    .set("status", DocumentTaskStatus.RETRY_WAIT.getValue())
                                    .set("next_retry_at", nextRetryAt)
                                    .set("lease_until", null)
                                    .set("last_error_code", "LEASE_EXPIRED")
                                    .set("last_error_message", "Processing lease expired, retrying")
                                    .setSql("version = version + 1"));

                    if (taskUpdated == 0) {
                        log.info("Stale PROCESSING task {} CAS failed (status changed or attempt={} no longer current), skipping",
                                task.getId(), fenceAttempt);
                        return null;
                    }

                    int docUpdated = documentMapper.update(null,
                            new UpdateWrapper<KnowledgeDocument>()
                                    .eq("id", task.getDocumentId())
                                    .eq("status", DocumentStatus.PROCESSING.getValue())
                                    .set("status", DocumentStatus.PENDING.getValue())
                                    .setSql("version = version + 1"));

                    if (docUpdated == 0) {
                        log.warn("Recovered task {} -> RETRY_WAIT but document {} CAS failed (status no longer PROCESSING), rolling back",
                                task.getId(), task.getDocumentId());
                        status.setRollbackOnly();
                        return null;
                    }

                    log.warn("Recovered stale PROCESSING task {} -> RETRY_WAIT, attempt={}/{}",
                            task.getId(), task.getAttemptCount(), task.getMaxAttempts());
                } else {
                    // Attempts exhausted: CAS PROCESSING -> DEAD with attempt_count fence
                    int taskUpdated = taskMapper.update(null,
                            new UpdateWrapper<DocumentIndexTask>()
                                    .eq("id", task.getId())
                                    .eq("status", DocumentTaskStatus.PROCESSING.getValue())
                                    .eq("attempt_count", fenceAttempt)
                                    .set("status", DocumentTaskStatus.DEAD.getValue())
                                    .set("last_error_code", "LEASE_EXPIRED_MAX_ATTEMPTS")
                                    .set("last_error_message", "Processing lease expired after max attempts")
                                    .set("lease_until", null)
                                    .set("completed_at", now)
                                    .setSql("version = version + 1"));

                    if (taskUpdated == 0) {
                        log.info("Stale PROCESSING task {} CAS failed (status changed or attempt={} no longer current), skipping",
                                task.getId(), fenceAttempt);
                        return null;
                    }

                    int docUpdated = documentMapper.update(null,
                            new UpdateWrapper<KnowledgeDocument>()
                                    .eq("id", task.getDocumentId())
                                    .eq("status", DocumentStatus.PROCESSING.getValue())
                                    .set("status", DocumentStatus.FAILED.getValue())
                                    .set("failure_code", "LEASE_EXPIRED_MAX_ATTEMPTS")
                                    .set("failure_message", "Processing lease expired after max attempts")
                                    .setSql("version = version + 1"));

                    if (docUpdated == 0) {
                        log.warn("Recovered task {} -> DEAD but document {} CAS failed (status no longer PROCESSING), rolling back",
                                task.getId(), task.getDocumentId());
                        status.setRollbackOnly();
                        return null;
                    }

                    log.warn("Recovered stale PROCESSING task {} -> DEAD (attempts exhausted)", task.getId());
                }

                return null;
            });
        }
    }

    /**
     * Recover stale UPLOADING documents that have been stuck.
     * CAS UPLOADING -> DELETING with UPLOAD_COMPENSATION.
     */
    private void recoverStaleUploading() {
        LocalDateTime abandonThreshold = LocalDateTime.now()
                .minusSeconds(ingestionProperties.getUploadAbandonedTimeoutSeconds());

        List<KnowledgeDocument> staleDocs = documentMapper.selectList(
                Wrappers.<KnowledgeDocument>query()
                        .eq("status", DocumentStatus.UPLOADING.getValue())
                        .lt("created_at", abandonThreshold)
                        .orderByAsc("created_at")
                        .last("LIMIT " + BATCH_SIZE));

        for (KnowledgeDocument doc : staleDocs) {
            transactionTemplate.execute(status -> {
                LocalDateTime now = LocalDateTime.now();
                int updated = documentMapper.update(null,
                        new UpdateWrapper<KnowledgeDocument>()
                                .eq("id", doc.getId())
                                .eq("status", DocumentStatus.UPLOADING.getValue())
                                .set("status", DocumentStatus.DELETING.getValue())
                                .set("cleanup_reason", "UPLOAD_COMPENSATION")
                                .set("cleanup_eligible_at",
                                        now.plusSeconds(ingestionProperties.getUploadCleanupGraceSeconds()))
                                .set("failure_code", "UPLOAD_ABANDONED")
                                .set("failure_message", "Upload abandoned, entered compensation cleanup")
                                .setSql("version = version + 1"));

                if (updated > 0) {
                    log.warn("Marked stale UPLOADING document {} as DELETING (UPLOAD_COMPENSATION)", doc.getId());
                }
                return null;
            });
        }
    }

    /**
     * Clean up DELETING documents whose cleanup_eligible_at has passed.
     * Delete MinIO object, then hard delete DB row.
     */
    private void cleanupDeletingDocuments() {
        List<KnowledgeDocument> deletingDocs = documentMapper.selectList(
                Wrappers.<KnowledgeDocument>query()
                        .eq("status", DocumentStatus.DELETING.getValue())
                        .le("cleanup_eligible_at", LocalDateTime.now())
                        .orderByAsc("cleanup_eligible_at")
                        .last("LIMIT " + BATCH_SIZE));

        for (KnowledgeDocument doc : deletingDocs) {
            try {
                // Delete MinIO object (NoSuchKey is ok)
                try {
                    objectStorageService.deleteObject(doc.getBucketName(), doc.getObjectKey());
                } catch (StorageException e) {
                    log.warn("Failed to delete MinIO object for document {}: {}", doc.getId(), e.getMessage());
                    // Keep DELETING, will retry next cycle
                    continue;
                }

                // Hard delete from DB (Chunks/Tasks cascade via FK)
                transactionTemplate.execute(status -> {
                    documentMapper.deleteById(doc.getId());
                    return null;
                });

                log.info("Cleaned up DELETING document {}: object deleted, DB row removed", doc.getId());
            } catch (Exception e) {
                log.error("Failed to cleanup DELETING document {}", doc.getId(), e);
            }
        }
    }

    /**
     * Dispatch DEAD tasks to DLQ for diagnostics.
     */
    private void dispatchDeadToDlq() {
        List<DocumentIndexTask> deadTasks = taskMapper.selectList(
                Wrappers.<DocumentIndexTask>query()
                        .eq("status", DocumentTaskStatus.DEAD.getValue())
                        .isNull("dead_lettered_at")
                        .orderByAsc("completed_at")
                        .last("LIMIT " + BATCH_SIZE));

        for (DocumentIndexTask task : deadTasks) {
            DocumentTaskPublisher.PublishResult result = publisher.publishDlq(task);
            if (result.isSuccess()) {
                taskMapper.update(null,
                        new UpdateWrapper<DocumentIndexTask>()
                                .eq("id", task.getId())
                                .eq("status", DocumentTaskStatus.DEAD.getValue())
                                .isNull("dead_lettered_at")
                                .set("dead_lettered_at", LocalDateTime.now()));
                log.info("Dispatched DEAD task {} to DLQ", task.getId());
            } else {
                log.warn("Failed to dispatch DEAD task {} to DLQ: isAcked={}, isReturned={}, isTimeout={}",
                        task.getId(), result.isAcked(), result.isReturned(), result.isTimeout());
            }
        }
    }
}