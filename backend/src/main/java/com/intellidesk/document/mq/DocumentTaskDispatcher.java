package com.intellidesk.document.mq;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.intellidesk.document.DocumentIndexTask;
import com.intellidesk.document.DocumentIndexTaskMapper;
import com.intellidesk.document.model.DocumentTaskStatus;
import com.intellidesk.infrastructure.config.DocumentIngestionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@Profile("!test")
public class DocumentTaskDispatcher {

    private final DocumentIndexTaskMapper taskMapper;
    private final DocumentTaskPublisher publisher;
    private final DocumentIngestionProperties ingestionProperties;

    private static final int BATCH_SIZE = 20;

    public DocumentTaskDispatcher(DocumentIndexTaskMapper taskMapper,
                                   DocumentTaskPublisher publisher,
                                   DocumentIngestionProperties ingestionProperties) {
        this.taskMapper = taskMapper;
        this.publisher = publisher;
        this.ingestionProperties = ingestionProperties;
    }

    /**
     * Scan and dispatch PENDING, due RETRY_WAIT, and stale QUEUED tasks.
     * Does NOT scan DEAD.
     */
    @Scheduled(fixedDelayString = "${intellidesk.document-ingestion.dispatcher-interval-seconds:10}000")
    public void dispatch() {
        dispatchPendingTasks();
        dispatchRetryWaitTasks();
        dispatchStaleQueuedTasks();
    }

    /**
     * PENDING tasks that haven't been confirmed dispatched.
     */
    private void dispatchPendingTasks() {
        List<DocumentIndexTask> pendingTasks = taskMapper.selectList(
                Wrappers.<DocumentIndexTask>query()
                        .eq("status", DocumentTaskStatus.PENDING.getValue())
                        .orderByAsc("created_at")
                        .last("LIMIT " + BATCH_SIZE));

        for (DocumentIndexTask task : pendingTasks) {
            DocumentTaskPublisher.PublishResult result = publisher.publishMain(task);
            if (result.isSuccess()) {
                int updated = taskMapper.update(null,
                        new UpdateWrapper<DocumentIndexTask>()
                                .eq("id", task.getId())
                                .eq("status", DocumentTaskStatus.PENDING.getValue())
                                .set("status", DocumentTaskStatus.QUEUED.getValue())
                                .set("last_dispatched_at", LocalDateTime.now())
                                .setSql("version = version + 1"));
                if (updated > 0) {
                    log.debug("Dispatched PENDING task {} -> QUEUED", task.getId());
                }
            } else {
                log.warn("Failed to dispatch PENDING task {}: isAcked={}, isReturned={}, isTimeout={}",
                        task.getId(), result.isAcked(), result.isReturned(), result.isTimeout());
            }
        }
    }

    /**
     * RETRY_WAIT tasks whose next_retry_at has passed.
     * Publishes directly to main queue (delay already satisfied in DB).
     */
    private void dispatchRetryWaitTasks() {
        List<DocumentIndexTask> retryTasks = taskMapper.selectList(
                Wrappers.<DocumentIndexTask>query()
                        .eq("status", DocumentTaskStatus.RETRY_WAIT.getValue())
                        .le("next_retry_at", LocalDateTime.now())
                        .orderByAsc("next_retry_at")
                        .last("LIMIT " + BATCH_SIZE));

        for (DocumentIndexTask task : retryTasks) {
            DocumentTaskPublisher.PublishResult result = publisher.publishMain(task);
            if (result.isSuccess()) {
                int updated = taskMapper.update(null,
                        new UpdateWrapper<DocumentIndexTask>()
                                .eq("id", task.getId())
                                .eq("status", DocumentTaskStatus.RETRY_WAIT.getValue())
                                .set("status", DocumentTaskStatus.QUEUED.getValue())
                                .set("last_dispatched_at", LocalDateTime.now())
                                .setSql("version = version + 1"));
                if (updated > 0) {
                    log.debug("Dispatched RETRY_WAIT task {} -> QUEUED", task.getId());
                }
            } else {
                log.warn("Failed to dispatch RETRY_WAIT task {}: isAcked={}, isReturned={}, isTimeout={}",
                        task.getId(), result.isAcked(), result.isReturned(), result.isTimeout());
            }
        }
    }

    /**
     * QUEUED tasks that have been stale beyond dispatch-stale-timeout.
     * Only re-dispatch if next_retry_at is null or past.
     */
    private void dispatchStaleQueuedTasks() {
        LocalDateTime staleThreshold = LocalDateTime.now()
                .minusSeconds(ingestionProperties.getDispatchStaleTimeoutSeconds());

        List<DocumentIndexTask> staleTasks = taskMapper.selectList(
                Wrappers.<DocumentIndexTask>query()
                        .eq("status", DocumentTaskStatus.QUEUED.getValue())
                        .lt("last_dispatched_at", staleThreshold)
                        .and(w -> w.isNull("next_retry_at").or().le("next_retry_at", LocalDateTime.now()))
                        .orderByAsc("last_dispatched_at")
                        .last("LIMIT " + BATCH_SIZE));

        for (DocumentIndexTask task : staleTasks) {
            DocumentTaskPublisher.PublishResult result = publisher.publishMain(task);
            if (result.isSuccess()) {
                taskMapper.update(null,
                        new UpdateWrapper<DocumentIndexTask>()
                                .eq("id", task.getId())
                                .set("last_dispatched_at", LocalDateTime.now()));
                log.debug("Re-dispatched stale QUEUED task {}", task.getId());
            } else {
                log.warn("Failed to re-dispatch stale QUEUED task {}: isAcked={}, isReturned={}, isTimeout={}",
                        task.getId(), result.isAcked(), result.isReturned(), result.isTimeout());
            }
        }
    }
}