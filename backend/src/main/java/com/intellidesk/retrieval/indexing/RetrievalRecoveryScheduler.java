package com.intellidesk.retrieval.indexing;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.retrieval.task.DocumentRetrievalTask;
import com.intellidesk.retrieval.task.DocumentRetrievalTaskMapper;
import com.intellidesk.retrieval.task.RetrievalTaskStatus;
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
@ConditionalOnProperty(name = "intellidesk.retrieval.retrieval-worker-enabled", havingValue = "true", matchIfMissing = false)
public class RetrievalRecoveryScheduler {

    private final DocumentRetrievalTaskMapper taskMapper;
    private final RetrievalProperties retrievalProperties;
    private final TransactionTemplate transactionTemplate;

    private static final int BATCH_SIZE = 20;

    public RetrievalRecoveryScheduler(DocumentRetrievalTaskMapper taskMapper,
                                       RetrievalProperties retrievalProperties,
                                       PlatformTransactionManager transactionManager) {
        this.taskMapper = taskMapper;
        this.retrievalProperties = retrievalProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${intellidesk.retrieval.recovery-interval-ms:30000}")
    public void recover() {
        List<DocumentRetrievalTask> staleTasks = taskMapper.selectList(
                Wrappers.<DocumentRetrievalTask>query()
                        .eq("status", RetrievalTaskStatus.PROCESSING.getValue())
                        .lt("lease_until", LocalDateTime.now())
                        .orderByAsc("lease_until")
                        .last("LIMIT " + BATCH_SIZE));

        for (DocumentRetrievalTask task : staleTasks) {
            transactionTemplate.execute(status -> {
                LocalDateTime now = LocalDateTime.now();
                long fenceToken = task.getFenceToken();
                int generation = task.getGeneration();

                if (task.getAttemptCount() < task.getMaxAttempts()) {
                    LocalDateTime nextRetryAt = now.plusSeconds(retrievalProperties.getRetryDelaySeconds());
                    int updated = taskMapper.update(null,
                            new UpdateWrapper<DocumentRetrievalTask>()
                                    .eq("id", task.getId())
                                    .eq("status", RetrievalTaskStatus.PROCESSING.getValue())
                                    .eq("generation", generation)
                                    .eq("fence_token", fenceToken)
                                    .set("status", RetrievalTaskStatus.RETRY_WAIT.getValue())
                                    .set("next_retry_at", nextRetryAt)
                                    .set("lease_until", null)
                                    .set("last_error_code", "LEASE_EXPIRED")
                                    .set("last_error_message", "Processing lease expired, retrying")
                                    .setSql("version = version + 1"));

                    if (updated == 0) {
                        log.info("Stale PROCESSING retrieval task {} CAS failed (status/gen/fence changed), another recovery handled it",
                                task.getId());
                    } else {
                        log.warn("Recovered stale PROCESSING retrieval task {} -> RETRY_WAIT, attempt={}/{}",
                                task.getId(), task.getAttemptCount(), task.getMaxAttempts());
                    }
                } else {
                    int updated = taskMapper.update(null,
                            new UpdateWrapper<DocumentRetrievalTask>()
                                    .eq("id", task.getId())
                                    .eq("status", RetrievalTaskStatus.PROCESSING.getValue())
                                    .eq("generation", generation)
                                    .eq("fence_token", fenceToken)
                                    .set("status", RetrievalTaskStatus.FAILED.getValue())
                                    .set("last_error_code", "LEASE_EXPIRED_MAX_ATTEMPTS")
                                    .set("last_error_message", "Processing lease expired after max attempts")
                                    .set("lease_until", null)
                                    .setSql("version = version + 1"));

                    if (updated == 0) {
                        log.info("Stale PROCESSING retrieval task {} CAS failed (status/gen/fence changed), another recovery handled it",
                                task.getId());
                    } else {
                        log.warn("Recovered stale PROCESSING retrieval task {} -> FAILED (attempts exhausted)", task.getId());
                    }
                }

                return null;
            });
        }
    }
}