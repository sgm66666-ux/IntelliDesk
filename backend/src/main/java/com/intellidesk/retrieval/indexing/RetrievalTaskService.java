package com.intellidesk.retrieval.indexing;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.embedding.EmbeddingService;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.retrieval.task.DocumentRetrievalTask;
import com.intellidesk.retrieval.task.DocumentRetrievalTaskMapper;
import com.intellidesk.retrieval.task.RetrievalTaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
public class RetrievalTaskService {

    private final DocumentRetrievalTaskMapper taskMapper;
    private final RetrievalProperties retrievalProperties;
    private final EmbeddingService embeddingService;

    public RetrievalTaskService(DocumentRetrievalTaskMapper taskMapper,
                                RetrievalProperties retrievalProperties,
                                EmbeddingService embeddingService) {
        this.taskMapper = taskMapper;
        this.retrievalProperties = retrievalProperties;
        this.embeddingService = embeddingService;
    }

    @Transactional
    public DocumentRetrievalTask createPendingTask(Long documentId, Long workspaceId, Long kbId, Long userId) {
        DocumentRetrievalTask task = new DocumentRetrievalTask();
        task.setDocumentId(documentId);
        task.setStatus(RetrievalTaskStatus.PENDING.getValue());
        task.setGeneration(1);
        task.setAttemptCount(0);
        task.setMaxAttempts(retrievalProperties.getMaxAttempts());
        task.setFenceToken(0L);
        task.setMessageId(UUID.randomUUID().toString());
        task.setEmbeddingModel(embeddingService.model());
        task.setEmbeddingDimension(embeddingService.dimension());
        task.setEsIndexName(retrievalProperties.getEsIndexName());
        task.setIndexedChunkCount(0);
        task.setRequestedBy(userId);
        task.setVersion(0);
        taskMapper.insert(task);
        log.info("Created retrieval task {} for document {}", task.getId(), documentId);
        return task;
    }

    public void cancelTask(Long documentId) {
        int updated = taskMapper.update(null,
                new UpdateWrapper<DocumentRetrievalTask>()
                        .eq("document_id", documentId)
                        .in("status", RetrievalTaskStatus.PENDING.getValue(),
                                RetrievalTaskStatus.QUEUED.getValue(),
                                RetrievalTaskStatus.PROCESSING.getValue(),
                                RetrievalTaskStatus.RETRY_WAIT.getValue())
                        .set("status", RetrievalTaskStatus.CANCELLED.getValue())
                        .setSql("version = version + 1"));

        if (updated > 0) {
            log.info("Cancelled retrieval task for document {}", documentId);
        }
    }

    @Transactional
    public DocumentRetrievalTask reindex(Long documentId, Long userId) {
        DocumentRetrievalTask task = taskMapper.selectOne(
                Wrappers.<DocumentRetrievalTask>query()
                        .eq("document_id", documentId)
                        .orderByDesc("created_at")
                        .last("LIMIT 1"));

        if (task == null) {
            throw new BusinessException(ErrorCode.RETRIEVAL_TASK_NOT_FOUND);
        }

        String currentStatus = task.getStatus();
        if (!RetrievalTaskStatus.READY.getValue().equals(currentStatus)
                && !RetrievalTaskStatus.FAILED.getValue().equals(currentStatus)) {
            throw new BusinessException(ErrorCode.RETRIEVAL_REINDEX_NOT_ALLOWED);
        }

        int newGeneration = task.getGeneration() + 1;
        int updated = taskMapper.update(null,
                new UpdateWrapper<DocumentRetrievalTask>()
                        .eq("id", task.getId())
                        .eq("status", currentStatus)
                        .set("status", RetrievalTaskStatus.PENDING.getValue())
                        .set("generation", newGeneration)
                        .set("attempt_count", 0)
                        .set("message_id", UUID.randomUUID())
                        .set("last_error_code", null)
                        .set("last_error_message", null)
                        .set("ready_at", null)
                        .set("next_retry_at", null)
                        .set("lease_until", null)
                        .set("started_at", null)
                        .set("indexed_chunk_count", 0)
                        .set("requested_by", userId)
                        .setSql("version = version + 1"));

        if (updated == 0) {
            throw new BusinessException(ErrorCode.RETRIEVAL_REINDEX_NOT_ALLOWED);
        }

        DocumentRetrievalTask updatedTask = taskMapper.selectById(task.getId());
        log.info("Reindex requested for document {}: generation {} -> {}, task {}",
                documentId, task.getGeneration(), newGeneration, task.getId());
        return updatedTask;
    }

    public DocumentRetrievalTask getTaskByDocumentId(Long documentId) {
        return taskMapper.selectOne(
                Wrappers.<DocumentRetrievalTask>query()
                        .eq("document_id", documentId)
                        .orderByDesc("created_at")
                        .last("LIMIT 1"));
    }
}