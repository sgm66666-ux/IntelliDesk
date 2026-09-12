package com.intellidesk.retrieval.indexing.mq;

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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@Profile("!test")
@ConditionalOnProperty(name = "intellidesk.retrieval.retrieval-worker-enabled", havingValue = "true", matchIfMissing = false)
public class RetrievalTaskDispatcher {

    private final DocumentRetrievalTaskMapper taskMapper;
    private final RetrievalTaskPublisher publisher;
    private final RetrievalProperties retrievalProperties;

    private static final int BATCH_SIZE = 20;

    public RetrievalTaskDispatcher(DocumentRetrievalTaskMapper taskMapper,
                                    RetrievalTaskPublisher publisher,
                                    RetrievalProperties retrievalProperties) {
        this.taskMapper = taskMapper;
        this.publisher = publisher;
        this.retrievalProperties = retrievalProperties;
    }

    @Scheduled(fixedDelayString = "${intellidesk.retrieval.dispatcher-interval-ms:5000}")
    public void dispatch() {
        dispatchPendingTasks();
        dispatchRetryWaitTasks();
    }

    private void dispatchPendingTasks() {
        List<DocumentRetrievalTask> pendingTasks = taskMapper.selectList(
                Wrappers.<DocumentRetrievalTask>query()
                        .eq("status", RetrievalTaskStatus.PENDING.getValue())
                        .orderByAsc("created_at")
                        .last("LIMIT " + BATCH_SIZE));

        for (DocumentRetrievalTask task : pendingTasks) {
            RetrievalIndexMessage message = RetrievalIndexMessage.create(
                    task.getMessageId(),
                    task.getId(),
                    task.getDocumentId(),
                    task.getGeneration());

            RetrievalTaskPublisher.PublishResult result = publisher.publish(message);
            if (result.isSuccess()) {
                int updated = taskMapper.update(null,
                        new UpdateWrapper<DocumentRetrievalTask>()
                                .eq("id", task.getId())
                                .eq("status", RetrievalTaskStatus.PENDING.getValue())
                                .set("status", RetrievalTaskStatus.QUEUED.getValue())
                                .set("last_dispatched_at", LocalDateTime.now())
                                .setSql("version = version + 1"));
                if (updated > 0) {
                    log.debug("Dispatched PENDING retrieval task {} -> QUEUED", task.getId());
                }
            } else {
                log.warn("Failed to dispatch PENDING retrieval task {}: isAcked={}, isReturned={}, isTimeout={}",
                        task.getId(), result.isAcked(), result.isReturned(), result.isTimeout());
            }
        }
    }

    private void dispatchRetryWaitTasks() {
        List<DocumentRetrievalTask> retryTasks = taskMapper.selectList(
                Wrappers.<DocumentRetrievalTask>query()
                        .eq("status", RetrievalTaskStatus.RETRY_WAIT.getValue())
                        .and(w -> w.isNull("next_retry_at").or().le("next_retry_at", LocalDateTime.now()))
                        .orderByAsc("next_retry_at")
                        .last("LIMIT " + BATCH_SIZE));

        for (DocumentRetrievalTask task : retryTasks) {
            RetrievalIndexMessage message = RetrievalIndexMessage.create(
                    task.getMessageId(),
                    task.getId(),
                    task.getDocumentId(),
                    task.getGeneration());

            RetrievalTaskPublisher.PublishResult result = publisher.publish(message);
            if (result.isSuccess()) {
                int updated = taskMapper.update(null,
                        new UpdateWrapper<DocumentRetrievalTask>()
                                .eq("id", task.getId())
                                .eq("status", RetrievalTaskStatus.RETRY_WAIT.getValue())
                                .set("status", RetrievalTaskStatus.QUEUED.getValue())
                                .set("last_dispatched_at", LocalDateTime.now())
                                .setSql("version = version + 1"));
                if (updated > 0) {
                    log.debug("Dispatched RETRY_WAIT retrieval task {} -> QUEUED", task.getId());
                }
            } else {
                log.warn("Failed to dispatch RETRY_WAIT retrieval task {}: isAcked={}, isReturned={}, isTimeout={}",
                        task.getId(), result.isAcked(), result.isReturned(), result.isTimeout());
            }
        }
    }
}