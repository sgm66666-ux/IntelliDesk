package com.intellidesk.document;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.document.chunk.ChunkConfig;
import com.intellidesk.document.chunk.ChunkDraft;
import com.intellidesk.document.chunk.ChunkStrategy;
import com.intellidesk.document.chunk.ChunkStrategyRegistry;
import com.intellidesk.document.chunk.ChunkStrategyType;
import com.intellidesk.document.model.DocumentFormat;
import com.intellidesk.document.model.DocumentStatus;
import com.intellidesk.document.model.DocumentTaskStatus;
import com.intellidesk.document.parser.DocumentParseException;
import com.intellidesk.document.parser.DocumentParser;
import com.intellidesk.document.parser.ParseContext;
import com.intellidesk.document.parser.ParsedDocument;
import com.intellidesk.document.parser.ParserRegistry;
import com.intellidesk.embedding.EmbeddingService;
import com.intellidesk.infrastructure.config.DocumentIngestionProperties;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.infrastructure.storage.ObjectStorageService;
import com.intellidesk.infrastructure.storage.StorageException;
import com.intellidesk.knowledge.KnowledgeBase;
import com.intellidesk.knowledge.KnowledgeBaseMapper;
import com.intellidesk.retrieval.task.DocumentRetrievalTask;
import com.intellidesk.retrieval.task.DocumentRetrievalTaskMapper;
import com.intellidesk.retrieval.task.RetrievalTaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class DocumentProcessingService {

    private final DocumentMapper documentMapper;
    private final DocumentIndexTaskMapper taskMapper;
    private final DocumentChunkMapper chunkMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ObjectStorageService objectStorageService;
    private final ParserRegistry parserRegistry;
    private final ChunkStrategyRegistry chunkStrategyRegistry;
    private final DocumentIngestionProperties ingestionProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final DocumentRetrievalTaskMapper retrievalTaskMapper;
    private final EmbeddingService embeddingService;
    private final RetrievalProperties retrievalProperties;

    public DocumentProcessingService(DocumentMapper documentMapper,
                                      DocumentIndexTaskMapper taskMapper,
                                      DocumentChunkMapper chunkMapper,
                                      KnowledgeBaseMapper knowledgeBaseMapper,
                                      ObjectStorageService objectStorageService,
                                      ParserRegistry parserRegistry,
                                      ChunkStrategyRegistry chunkStrategyRegistry,
                                      DocumentIngestionProperties ingestionProperties,
                                      ObjectMapper objectMapper,
                                      PlatformTransactionManager transactionManager,
                                      DocumentRetrievalTaskMapper retrievalTaskMapper,
                                      EmbeddingService embeddingService,
                                      RetrievalProperties retrievalProperties) {
        this.documentMapper = documentMapper;
        this.taskMapper = taskMapper;
        this.chunkMapper = chunkMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.objectStorageService = objectStorageService;
        this.parserRegistry = parserRegistry;
        this.chunkStrategyRegistry = chunkStrategyRegistry;
        this.ingestionProperties = ingestionProperties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.retrievalTaskMapper = retrievalTaskMapper;
        this.embeddingService = embeddingService;
        this.retrievalProperties = retrievalProperties;
    }

    /**
     * Atomically claim the task and document in a single transaction.
     * Returns the fence token (attempt number) on success, null on failure.
     */
    public ClaimResult claimTask(DocumentIndexTask task, KnowledgeDocument document) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime leaseUntil = now.plusMinutes(ingestionProperties.getProcessingLeaseMinutes());

            int newAttempt = task.getAttemptCount() + 1;

            // Note: No version CAS on task claim — status check is sufficient for mutual exclusion.
            // Version check would cause race condition with upload flow's PENDING→QUEUED update.
            int taskUpdated = taskMapper.update(null,
                    new UpdateWrapper<DocumentIndexTask>()
                            .eq("id", task.getId())
                            .in("status", DocumentTaskStatus.PENDING.getValue(),
                                    DocumentTaskStatus.QUEUED.getValue(),
                                    DocumentTaskStatus.RETRY_WAIT.getValue())
                            .set("status", DocumentTaskStatus.PROCESSING.getValue())
                            .set("attempt_count", newAttempt)
                            .set("lease_until", leaseUntil)
                            .set("started_at", now)
                            .setSql("version = version + 1"));

            if (taskUpdated == 0) {
                return null;
            }

            // Document version CAS is still useful: prevents claim if document was already
            // moved to PROCESSING by another consumer (e.g., stale message redelivery).
            int docUpdated = documentMapper.update(null,
                    new UpdateWrapper<KnowledgeDocument>()
                            .eq("id", document.getId())
                            .eq("version", document.getVersion())
                            .eq("status", DocumentStatus.PENDING.getValue())
                            .set("status", DocumentStatus.PROCESSING.getValue())
                            .setSql("version = version + 1"));

            if (docUpdated == 0) {
                status.setRollbackOnly();
                return null;
            }

            return new ClaimResult(newAttempt, leaseUntil);
        });
    }

    /**
     * Fence token returned by claimTask, used to verify the same attempt in all subsequent operations.
     */
    public record ClaimResult(int attemptNumber, LocalDateTime leaseUntil) {
    }

    /**
     * Process the document: download, parse, chunk, persist.
     * @param fenceAttempt the attempt number from the claim, used to protect against stale worker
     */
    public void process(DocumentIndexTask task, KnowledgeDocument document, KnowledgeBase knowledgeBase,
                         int fenceAttempt) {
        // Load fresh document after claim
        KnowledgeDocument freshDoc = documentMapper.selectById(document.getId());
        if (freshDoc == null) {
            log.warn("Document {} not found during processing, task={}", document.getId(), task.getId());
            return;
        }
        if (!DocumentStatus.PROCESSING.getValue().equals(freshDoc.getStatus())) {
            log.warn("Document {} status changed to {} during processing, task={}",
                    document.getId(), freshDoc.getStatus(), task.getId());
            return;
        }

        DocumentFormat format;
        try {
            format = DocumentFormat.fromExtension(freshDoc.getFileExtension());
        } catch (IllegalArgumentException e) {
            markPermanentFailure(task, freshDoc, "UNSUPPORTED_FORMAT",
                    "Unsupported format: " + freshDoc.getFileExtension(), fenceAttempt);
            return;
        }

        // Download from MinIO
        byte[] fileBytes;
        try (InputStream input = objectStorageService.getObject(
                freshDoc.getBucketName(), freshDoc.getObjectKey())) {
            fileBytes = input.readAllBytes();
        } catch (StorageException e) {
            if (e.isRetryable()) {
                markRetryableFailure(task, freshDoc, "STORAGE_UNAVAILABLE",
                        "Failed to download from MinIO: " + e.getMessage(), fenceAttempt);
            } else {
                markPermanentFailure(task, freshDoc, "STORAGE_UNAVAILABLE",
                        "Failed to download from MinIO: " + e.getMessage(), fenceAttempt);
            }
            return;
        } catch (Exception e) {
            markRetryableFailure(task, freshDoc, "DOWNLOAD_FAILED",
                    "Failed to download: " + e.getMessage(), fenceAttempt);
            return;
        }

        // Parse
        ParsedDocument parsedDocument;
        try {
            DocumentParser parser = parserRegistry.getParser(format);
            ParseContext context = ParseContext.builder()
                    .maxPdfPages(ingestionProperties.getMaxPdfPages())
                    .maxExtractedCharacters(ingestionProperties.getMaxExtractedCharacters())
                    .build();
            parsedDocument = parser.parse(new java.io.ByteArrayInputStream(fileBytes), context);
        } catch (DocumentParseException e) {
            if (e.isRetryable()) {
                markRetryableFailure(task, freshDoc, e.getErrorCode(), e.getMessage(), fenceAttempt);
            } else {
                markPermanentFailure(task, freshDoc, e.getErrorCode(), e.getMessage(), fenceAttempt);
            }
            return;
        } catch (Exception e) {
            markPermanentFailure(task, freshDoc, "PARSE_FAILED",
                    "Parse failed: " + e.getMessage(), fenceAttempt);
            return;
        }

        // Chunk
        ChunkStrategyType strategyType;
        try {
            strategyType = ChunkStrategyType.valueOf(freshDoc.getChunkStrategy());
        } catch (IllegalArgumentException e) {
            strategyType = ChunkStrategyType.RECURSIVE;
        }
        ChunkStrategy strategy = chunkStrategyRegistry.getStrategy(strategyType);
        ChunkConfig chunkConfig = new ChunkConfig(
                strategyType,
                freshDoc.getChunkSize(),
                freshDoc.getChunkOverlap());
        List<ChunkDraft> chunks;
        try {
            chunks = strategy.split(parsedDocument, chunkConfig);
        } catch (Exception e) {
            markPermanentFailure(task, freshDoc, "CHUNK_FAILED",
                    "Chunk splitting failed: " + e.getMessage(), fenceAttempt);
            return;
        }

        if (chunks.isEmpty()) {
            markPermanentFailure(task, freshDoc, "EMPTY_CHUNKS",
                    "No chunks produced from document", fenceAttempt);
            return;
        }

        // Persist: recheck fence + replace chunks + update task + update document
        boolean persisted = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            // Fence check: verify task still belongs to this attempt
            DocumentIndexTask taskCheck = taskMapper.selectById(task.getId());
            if (taskCheck == null
                    || !DocumentTaskStatus.PROCESSING.getValue().equals(taskCheck.getStatus())
                    || taskCheck.getAttemptCount() != fenceAttempt) {
                log.warn("Task {} fence breached (expected attempt={}, actual={}), discarding stale result",
                        task.getId(), fenceAttempt,
                        taskCheck != null ? taskCheck.getAttemptCount() : "null");
                return false;
            }

            KnowledgeDocument docCheck = documentMapper.selectById(freshDoc.getId());
            if (docCheck == null
                    || !DocumentStatus.PROCESSING.getValue().equals(docCheck.getStatus())) {
                log.warn("Document {} not PROCESSING at persist time, actual={}",
                        freshDoc.getId(), docCheck != null ? docCheck.getStatus() : "null");
                return false;
            }

            // Delete old chunks
            chunkMapper.delete(Wrappers.<DocumentChunk>query()
                    .eq("document_id", freshDoc.getId()));

            // Insert new chunks
            List<DocumentChunk> chunkEntities = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                ChunkDraft draft = chunks.get(i);
                DocumentChunk chunk = new DocumentChunk();
                chunk.setKnowledgeBaseId(freshDoc.getKnowledgeBaseId());
                chunk.setDocumentId(freshDoc.getId());
                chunk.setChunkIndex(i);
                chunk.setContent(draft.getContent());
                chunk.setCharacterCount(draft.getCharacterCount());
                chunk.setTokenCount(draft.getTokenCount());
                chunk.setPageStart(draft.getPageStart());
                chunk.setPageEnd(draft.getPageEnd());
                chunk.setSectionPath(draft.getSectionPath());
                chunk.setStartOffset(draft.getStartOffset());
                chunk.setEndOffset(draft.getEndOffset());
                chunk.setSourceMetadata(toJson(draft.getSourceMetadata()));
                chunkEntities.add(chunk);
            }

            for (DocumentChunk chunk : chunkEntities) {
                chunkMapper.insert(chunk);
            }

            // Update task -> SUCCEEDED
            LocalDateTime now = LocalDateTime.now();
            int taskUpd = taskMapper.update(null,
                    new UpdateWrapper<DocumentIndexTask>()
                            .eq("id", task.getId())
                            .eq("status", DocumentTaskStatus.PROCESSING.getValue())
                            .eq("attempt_count", fenceAttempt)
                            .set("status", DocumentTaskStatus.SUCCEEDED.getValue())
                            .set("completed_at", now)
                            .set("lease_until", null)
                            .setSql("version = version + 1"));

            if (taskUpd == 0) {
                log.warn("Task {} fence breached during SUCCEEDED update, discarding", task.getId());
                status.setRollbackOnly();
                return false;
            }

            // Update document -> COMPLETED
            String parserMetadata = buildParserMetadata(parsedDocument, format);
            PGobject parserMetadataJson = new PGobject();
            parserMetadataJson.setType("jsonb");
            try {
                parserMetadataJson.setValue(parserMetadata);
            } catch (java.sql.SQLException e) {
                throw new RuntimeException("Failed to set jsonb value", e);
            }
            documentMapper.update(null,
                    new UpdateWrapper<KnowledgeDocument>()
                            .eq("id", freshDoc.getId())
                            .eq("status", DocumentStatus.PROCESSING.getValue())
                            .set("status", DocumentStatus.COMPLETED.getValue())
                            .set("parser_metadata", parserMetadataJson)
                            .set("completed_at", now)
                            .setSql("version = version + 1"));

            // Phase 3 durable handoff: create retrieval task in same transaction
            createRetrievalTask(freshDoc.getId(), freshDoc.getKnowledgeBaseId());

            return true;
        }));

        if (!persisted) {
            log.warn("Failed to persist chunks for document {}, task={}", freshDoc.getId(), task.getId());
        }
    }

    private void markRetryableFailure(DocumentIndexTask task, KnowledgeDocument document,
                                       String errorCode, String errorMessage, int fenceAttempt) {
        int nextAttempt = task.getAttemptCount() + 1;
        if (nextAttempt >= task.getMaxAttempts()) {
            markPermanentFailure(task, document, errorCode,
                    "Max attempts (" + task.getMaxAttempts() + ") exhausted: " + errorMessage, fenceAttempt);
            return;
        }

        transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime nextRetryAt = now.plusSeconds(ingestionProperties.getRetryDelaySeconds());

            int updated = taskMapper.update(null,
                    new UpdateWrapper<DocumentIndexTask>()
                            .eq("id", task.getId())
                            .eq("status", DocumentTaskStatus.PROCESSING.getValue())
                            .eq("attempt_count", fenceAttempt)
                            .set("status", DocumentTaskStatus.RETRY_WAIT.getValue())
                            .set("next_retry_at", nextRetryAt)
                            .set("last_error_code", errorCode)
                            .set("last_error_message", truncate(errorMessage, 512))
                            .set("lease_until", null)
                            .setSql("version = version + 1"));

            if (updated == 0) {
                log.warn("Task {} fence breached during RETRY_WAIT update (attempt={}), discarding",
                        task.getId(), fenceAttempt);
                return null;
            }

            documentMapper.update(null,
                    new UpdateWrapper<KnowledgeDocument>()
                            .eq("id", document.getId())
                            .eq("status", DocumentStatus.PROCESSING.getValue())
                            .set("status", DocumentStatus.PENDING.getValue())
                            .setSql("version = version + 1"));

            return null;
        });
    }

    public void markPermanentFailure(DocumentIndexTask task, KnowledgeDocument document,
                                       String errorCode, String errorMessage, int fenceAttempt) {
        transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();

            int updated = taskMapper.update(null,
                    new UpdateWrapper<DocumentIndexTask>()
                            .eq("id", task.getId())
                            .eq("status", DocumentTaskStatus.PROCESSING.getValue())
                            .eq("attempt_count", fenceAttempt)
                            .set("status", DocumentTaskStatus.DEAD.getValue())
                            .set("last_error_code", errorCode)
                            .set("last_error_message", truncate(errorMessage, 512))
                            .set("lease_until", null)
                            .set("completed_at", now)
                            .setSql("version = version + 1"));

            if (updated == 0) {
                log.warn("Task {} fence breached during DEAD update (attempt={}), discarding",
                        task.getId(), fenceAttempt);
                return null;
            }

            documentMapper.update(null,
                    new UpdateWrapper<KnowledgeDocument>()
                            .eq("id", document.getId())
                            .eq("status", DocumentStatus.PROCESSING.getValue())
                            .set("status", DocumentStatus.FAILED.getValue())
                            .set("failure_code", errorCode)
                            .set("failure_message", truncate(errorMessage, 512))
                            .setSql("version = version + 1"));

            return null;
        });
    }

    private String buildParserMetadata(ParsedDocument parsedDocument, DocumentFormat format) {
        Map<String, Object> meta = new java.util.HashMap<>(parsedDocument.getMetadata());
        meta.put("parserFormat", format.name());
        return toJson(meta);
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata", e);
            return "{}";
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    /**
     * Phase 3 durable handoff: create a PENDING retrieval task.
     * Called inside the same transaction as chunk persistence + Document COMPLETED.
     * If this transaction rolls back, the retrieval task also does not exist.
     */
    private void createRetrievalTask(Long documentId, Long knowledgeBaseId) {
        DocumentRetrievalTask retrievalTask = new DocumentRetrievalTask();
        retrievalTask.setDocumentId(documentId);
        retrievalTask.setStatus(RetrievalTaskStatus.PENDING.getValue());
        retrievalTask.setGeneration(1);
        retrievalTask.setAttemptCount(0);
        retrievalTask.setMaxAttempts(retrievalProperties.getMaxAttempts());
        retrievalTask.setFenceToken(0L);
        retrievalTask.setMessageId(UUID.randomUUID().toString());
        retrievalTask.setEmbeddingModel(embeddingService.model());
        retrievalTask.setEmbeddingDimension(embeddingService.dimension());
        retrievalTask.setEsIndexName(retrievalProperties.getEsIndexName());
        retrievalTask.setIndexedChunkCount(0);
        retrievalTask.setVersion(0);
        retrievalTaskMapper.insert(retrievalTask);
        log.info("Created retrieval task {} for document {}", retrievalTask.getId(), documentId);
    }
}