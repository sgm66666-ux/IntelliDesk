package com.intellidesk.retrieval.indexing;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.intellidesk.document.DocumentChunk;
import com.intellidesk.document.DocumentChunkMapper;
import com.intellidesk.document.DocumentMapper;
import com.intellidesk.document.KnowledgeDocument;
import com.intellidesk.embedding.EmbeddingBatchResult;
import com.intellidesk.embedding.EmbeddingService;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.knowledge.KnowledgeBase;
import com.intellidesk.knowledge.KnowledgeBaseMapper;
import com.intellidesk.retrieval.keyword.ElasticsearchChunkDocument;
import com.intellidesk.retrieval.keyword.ElasticsearchChunkIndex;
import com.intellidesk.retrieval.task.DocumentRetrievalTask;
import com.intellidesk.retrieval.task.DocumentRetrievalTaskMapper;
import com.intellidesk.retrieval.task.RetrievalTaskStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@Profile("!test")
public class RetrievalIndexingService {

    private final DocumentRetrievalTaskMapper taskMapper;
    private final DocumentChunkMapper chunkMapper;
    private final EmbeddingService embeddingService;
    private final ElasticsearchChunkIndex elasticsearchChunkIndex;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final RetrievalProperties retrievalProperties;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public RetrievalIndexingService(DocumentRetrievalTaskMapper taskMapper,
                                    DocumentChunkMapper chunkMapper,
                                    EmbeddingService embeddingService,
                                    ElasticsearchChunkIndex elasticsearchChunkIndex,
                                    KnowledgeBaseMapper knowledgeBaseMapper,
                                    DocumentMapper documentMapper,
                                    RetrievalProperties retrievalProperties,
                                    PlatformTransactionManager transactionManager,
                                    ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingService = embeddingService;
        this.elasticsearchChunkIndex = elasticsearchChunkIndex;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.retrievalProperties = retrievalProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    public void claimAndProcess(DocumentRetrievalTask task) {
        ClaimResult claim = claim(task);
        if (!claim.claimed()) {
            log.debug("Task {} claim failed (already claimed by another consumer)", task.getId());
            return;
        }

        try {
            processIndexing(task, claim);
        } catch (Exception e) {
            log.error("Processing failed for task {}: {}", task.getId(), e.getMessage(), e);
            // Failure handling is done inside processIndexing
        }
    }

    private ClaimResult claim(DocumentRetrievalTask task) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime leaseUntil = now.plusSeconds(retrievalProperties.getProcessingLeaseSeconds());

            int newAttempt = task.getAttemptCount() + 1;
            long newFenceToken = task.getFenceToken() + 1;

            int updated = taskMapper.update(null,
                    new UpdateWrapper<DocumentRetrievalTask>()
                            .eq("id", task.getId())
                            .in("status", RetrievalTaskStatus.PENDING.getValue(),
                                    RetrievalTaskStatus.QUEUED.getValue(),
                                    RetrievalTaskStatus.RETRY_WAIT.getValue())
                            .set("status", RetrievalTaskStatus.PROCESSING.getValue())
                            .set("attempt_count", newAttempt)
                            .set("fence_token", newFenceToken)
                            .set("lease_until", leaseUntil)
                            .set("started_at", now)
                            .setSql("version = version + 1"));

            if (updated == 0) {
                return ClaimResult.notClaimed();
            }

            return new ClaimResult(task.getGeneration(), newAttempt, newFenceToken, true);
        });
    }

    private void processIndexing(DocumentRetrievalTask task, ClaimResult claim) {
        // 1. Load all chunks for document
        List<DocumentChunk> chunks = chunkMapper.selectList(
                Wrappers.<DocumentChunk>query()
                        .eq("document_id", task.getDocumentId())
                        .orderByAsc("chunk_index"));

        if (chunks.isEmpty()) {
            log.warn("No chunks found for document {}, task {}", task.getDocumentId(), task.getId());
            markRetryableFailure(task, claim, "NO_CHUNKS",
                    "No chunks found for document");
            return;
        }

        // 2. Embed all chunks
        List<String> chunkContents = chunks.stream()
                .map(DocumentChunk::getContent)
                .collect(Collectors.toList());

        EmbeddingBatchResult batchResult;
        try {
            batchResult = embeddingService.embedDocuments(chunkContents);
        } catch (Exception e) {
            log.error("Embedding failed for document {}, task {}", task.getDocumentId(), task.getId(), e);
            markRetryableFailure(task, claim, "EMBEDDING_FAILED",
                    "Embedding failed: " + e.getMessage());
            return;
        }

        if (batchResult.getEmbeddings().size() != chunks.size()) {
            log.error("Embedding count mismatch: expected {}, got {} for document {}",
                    chunks.size(), batchResult.getEmbeddings().size(), task.getDocumentId());
            markRetryableFailure(task, claim, "EMBEDDING_MISMATCH",
                    "Embedding count mismatch");
            return;
        }

        // 3. Short DB txn: verify fence, update chunk embeddings
        boolean embeddingsUpdated = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // Fence check
            DocumentRetrievalTask taskCheck = taskMapper.selectById(task.getId());
            if (taskCheck == null
                    || !RetrievalTaskStatus.PROCESSING.getValue().equals(taskCheck.getStatus())
                    || taskCheck.getGeneration() != claim.generation()
                    || !taskCheck.getFenceToken().equals(claim.fenceToken())) {
                log.warn("Task {} fence breached during embedding update (gen={}, fence={}), discarding",
                        task.getId(), claim.generation(), claim.fenceToken());
                return false;
            }

            LocalDateTime now = LocalDateTime.now();
            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = chunks.get(i);
                chunkMapper.update(null,
                        new UpdateWrapper<DocumentChunk>()
                                .eq("id", chunk.getId())
                                .set("embedding", new PGvector(batchResult.getEmbeddings().get(i)))
                                .set("embedding_model", batchResult.getModel())
                                .set("embedding_generation", claim.generation())
                                .set("embedding_fence_token", claim.fenceToken())
                                .set("embedded_at", now));
            }

            return true;
        }));

        if (!embeddingsUpdated) {
            return;
        }

        // 4. Recheck fence before ES operations
        DocumentRetrievalTask fenceCheck = taskMapper.selectById(task.getId());
        if (fenceCheck == null
                || !RetrievalTaskStatus.PROCESSING.getValue().equals(fenceCheck.getStatus())
                || fenceCheck.getGeneration() != claim.generation()
                || !fenceCheck.getFenceToken().equals(claim.fenceToken())) {
            log.warn("Task {} fence breached before ES index, discarding", task.getId());
            return;
        }

        // 5. Build ES docs (workspaceId from KB chain)
        KnowledgeDocument document = documentMapper.selectById(task.getDocumentId());
        if (document == null) {
            markPermanentFailure(task, claim, "DOCUMENT_NOT_FOUND",
                    "Document not found");
            return;
        }

        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(document.getKnowledgeBaseId());
        if (knowledgeBase == null) {
            markPermanentFailure(task, claim, "KB_NOT_FOUND",
                    "Knowledge base not found");
            return;
        }

        List<ElasticsearchChunkDocument> esDocs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            ElasticsearchChunkDocument doc = new ElasticsearchChunkDocument();
            doc.setChunkId(chunk.getId());
            doc.setDocumentId(task.getDocumentId());
            doc.setKnowledgeBaseId(document.getKnowledgeBaseId());
            doc.setWorkspaceId(knowledgeBase.getWorkspaceId());
            doc.setChunkIndex(chunk.getChunkIndex());
            doc.setContent(chunk.getContent());
            doc.setMetadata(parseMetadata(chunk.getSourceMetadata()));
            doc.setIndexGeneration(claim.generation());
            doc.setFenceToken(claim.fenceToken());
            doc.setIndexedAt(Instant.now());
            esDocs.add(doc);
        }

        // 6. ES bulk index with external version = fenceToken
        try {
            elasticsearchChunkIndex.bulkIndex(esDocs, claim.fenceToken());
        } catch (Exception e) {
            log.error("ES bulk index failed for document {}, task {}: {}",
                    task.getDocumentId(), task.getId(), e.getMessage());
            markRetryableFailure(task, claim, "ES_INDEX_FAILED",
                    "ES bulk index failed: " + e.getMessage());
            return;
        }

        // 7. Short DB txn: PROCESSING -> READY
        Integer readyResult = transactionTemplate.execute(status -> {
            int updated = taskMapper.update(null,
                    new UpdateWrapper<DocumentRetrievalTask>()
                            .eq("id", task.getId())
                            .eq("status", RetrievalTaskStatus.PROCESSING.getValue())
                            .eq("generation", claim.generation())
                            .eq("fence_token", claim.fenceToken())
                            .set("status", RetrievalTaskStatus.READY.getValue())
                            .set("indexed_chunk_count", chunks.size())
                            .set("ready_at", LocalDateTime.now())
                            .set("lease_until", null)
                            .setSql("version = version + 1"));

            if (updated == 0) {
                log.warn("Task {} CAS PROCESSING->READY failed (status/gen/fence mismatch), stale worker", task.getId());
                return 0;
            }

            return updated;
        });

        if (readyResult != null && readyResult > 0) {
            log.info("Task {} completed: indexed {} chunks for document {} (generation={})",
                    task.getId(), chunks.size(), task.getDocumentId(), claim.generation());
        }
    }

    private void markRetryableFailure(DocumentRetrievalTask task, ClaimResult claim,
                                       String errorCode, String errorMessage) {
        if (claim.attemptCount() >= task.getMaxAttempts()) {
            markPermanentFailure(task, claim, errorCode,
                    "Max attempts (" + task.getMaxAttempts() + ") exhausted: " + errorMessage);
            return;
        }

        transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime nextRetryAt = now.plusSeconds(retrievalProperties.getRetryDelaySeconds());

            int updated = taskMapper.update(null,
                    new UpdateWrapper<DocumentRetrievalTask>()
                            .eq("id", task.getId())
                            .eq("status", RetrievalTaskStatus.PROCESSING.getValue())
                            .eq("generation", claim.generation())
                            .eq("fence_token", claim.fenceToken())
                            .set("status", RetrievalTaskStatus.RETRY_WAIT.getValue())
                            .set("next_retry_at", nextRetryAt)
                            .set("last_error_code", errorCode)
                            .set("last_error_message", truncate(errorMessage, 512))
                            .set("lease_until", null)
                            .setSql("version = version + 1"));

            if (updated == 0) {
                log.warn("Task {} fence breached during RETRY_WAIT update (gen={}, fence={}), discarding",
                        task.getId(), claim.generation(), claim.fenceToken());
                return null;
            }

            log.warn("Task {} -> RETRY_WAIT: {} (attempt {}/{})",
                    task.getId(), errorCode, claim.attemptCount(), task.getMaxAttempts());
            return null;
        });
    }

    private void markPermanentFailure(DocumentRetrievalTask task, ClaimResult claim,
                                        String errorCode, String errorMessage) {
        transactionTemplate.execute(status -> {
            int updated = taskMapper.update(null,
                    new UpdateWrapper<DocumentRetrievalTask>()
                            .eq("id", task.getId())
                            .eq("status", RetrievalTaskStatus.PROCESSING.getValue())
                            .eq("generation", claim.generation())
                            .eq("fence_token", claim.fenceToken())
                            .set("status", RetrievalTaskStatus.FAILED.getValue())
                            .set("last_error_code", errorCode)
                            .set("last_error_message", truncate(errorMessage, 512))
                            .set("lease_until", null)
                            .setSql("version = version + 1"));

            if (updated == 0) {
                log.warn("Task {} fence breached during FAILED update (gen={}, fence={}), discarding",
                        task.getId(), claim.generation(), claim.fenceToken());
                return null;
            }

            log.error("Task {} -> FAILED: {} (attempt {}/{})",
                    task.getId(), errorCode, claim.attemptCount(), task.getMaxAttempts());
            return null;
        });
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private Map<String, Object> parseMetadata(String sourceMetadata) {
        if (sourceMetadata == null || sourceMetadata.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(sourceMetadata, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse sourceMetadata as JSON, storing as raw string: {}", e.getMessage());
            return Collections.singletonMap("raw", sourceMetadata);
        }
    }
}