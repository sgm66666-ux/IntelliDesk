package com.intellidesk.document;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.common.dto.PageResponse;
import com.intellidesk.document.dto.DocumentChunkResponse;
import com.intellidesk.document.dto.DocumentResponse;
import com.intellidesk.document.dto.DocumentTaskResponse;
import com.intellidesk.document.dto.DocumentUploadResponse;
import com.intellidesk.document.dto.DocumentRetryResponse;
import com.intellidesk.document.model.DocumentFormat;
import com.intellidesk.document.model.DocumentStatus;
import com.intellidesk.document.model.DocumentTaskStatus;
import com.intellidesk.document.parser.DocumentFormatDetector;
import com.intellidesk.document.parser.DocumentParseException;
import com.intellidesk.document.mq.DocumentTaskPublisher;
import com.intellidesk.infrastructure.config.DocumentIngestionProperties;
import com.intellidesk.infrastructure.config.MinioProperties;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.infrastructure.storage.ObjectStorageService;
import com.intellidesk.infrastructure.storage.StorageException;
import com.intellidesk.knowledge.KnowledgeBase;
import com.intellidesk.knowledge.KnowledgeBaseService;
import com.intellidesk.retrieval.indexing.RetrievalCleanupService;
import com.intellidesk.retrieval.indexing.RetrievalTaskService;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class DocumentService {

    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectStorageService objectStorageService;
    private final DocumentMapper documentMapper;
    private final DocumentIndexTaskMapper taskMapper;
    private final DocumentChunkMapper chunkMapper;
    private final DocumentTaskService documentTaskService;
    private final DocumentFormatDetector formatDetector;
    private final MinioProperties minioProperties;
    private final DocumentIngestionProperties ingestionProperties;
    private final DocumentTaskPublisher documentTaskPublisher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final RetrievalCleanupService retrievalCleanupService;
    private final RetrievalTaskService retrievalTaskService;
    private final RetrievalProperties retrievalProperties;

    public DocumentService(WorkspaceAuthorizationService workspaceAuthorizationService,
                           KnowledgeBaseService knowledgeBaseService,
                           ObjectStorageService objectStorageService,
                           DocumentMapper documentMapper,
                           DocumentIndexTaskMapper taskMapper,
                           DocumentChunkMapper chunkMapper,
                           DocumentTaskService documentTaskService,
                           DocumentFormatDetector formatDetector,
                           MinioProperties minioProperties,
                           DocumentIngestionProperties ingestionProperties,
                           DocumentTaskPublisher documentTaskPublisher,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager,
                           RetrievalCleanupService retrievalCleanupService,
                           RetrievalTaskService retrievalTaskService,
                           RetrievalProperties retrievalProperties) {
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectStorageService = objectStorageService;
        this.documentMapper = documentMapper;
        this.taskMapper = taskMapper;
        this.chunkMapper = chunkMapper;
        this.documentTaskService = documentTaskService;
        this.formatDetector = formatDetector;
        this.minioProperties = minioProperties;
        this.ingestionProperties = ingestionProperties;
        this.documentTaskPublisher = documentTaskPublisher;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.retrievalCleanupService = retrievalCleanupService;
        this.retrievalTaskService = retrievalTaskService;
        this.retrievalProperties = retrievalProperties;
    }

    public DocumentUploadResponse uploadDocument(Long workspaceId, Long knowledgeBaseId, Long userId,
                                                  MultipartFile file) {
        workspaceAuthorizationService.requireOwner(workspaceId, userId);

        KnowledgeBase knowledgeBase = knowledgeBaseService.getKnowledgeBase(workspaceId, knowledgeBaseId, userId);

        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.DOCUMENT_EMPTY);
        }

        long maxFileSize = ingestionProperties.getMaxFileSizeBytes();
        if (file.getSize() > maxFileSize) {
            throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_EMPTY);
        }

        if (bytes.length == 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_EMPTY);
        }

        DocumentFormat format = formatDetector.detect(file.getOriginalFilename(), file.getContentType(), bytes);
        if (format == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_TYPE_UNSUPPORTED);
        }

        String checksum = calculateChecksum(bytes);
        checkDuplicate(knowledgeBaseId, checksum);

        String detectedContentType = resolveContentType(format);

        KnowledgeDocument document = transactionTemplate.execute(status -> {
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setKnowledgeBaseId(knowledgeBaseId);
            doc.setOriginalFileName(normalizeFileName(file.getOriginalFilename()));
            doc.setFileExtension(format.getExtension());
            doc.setContentType(detectedContentType);
            doc.setFileSize((long) bytes.length);
            doc.setChecksumSha256(checksum);
            doc.setBucketName(minioProperties.getBucket());
            doc.setObjectKey("pending/" + UUID.randomUUID());
            doc.setStatus(DocumentStatus.UPLOADING.getValue());
            doc.setChunkStrategy(knowledgeBase.getChunkStrategy());
            doc.setChunkSize(knowledgeBase.getChunkSize());
            doc.setChunkOverlap(knowledgeBase.getChunkOverlap());
            doc.setParserMetadata(toJson(Map.of("parserFormat", format.name())));
            doc.setCreatedBy(userId);
            doc.setVersion(0);
            documentMapper.insert(doc);

            String finalObjectKey = generateObjectKey(workspaceId, knowledgeBaseId, doc.getId(), format);
            doc.setObjectKey(finalObjectKey);
            documentMapper.updateById(doc);
            return doc;
        });

        if (document == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_UPLOAD_FAILED);
        }

        try {
            objectStorageService.putObject(
                    document.getBucketName(),
                    document.getObjectKey(),
                    new ByteArrayInputStream(bytes),
                    detectedContentType,
                    bytes.length
            );
        } catch (StorageException e) {
            log.warn("MinIO putObject failed for document {}, retryable={}", document.getId(), e.isRetryable(), e);
            compensateUploadingToDeleting(document.getId());
            throw new BusinessException(ErrorCode.DOCUMENT_UPLOAD_FAILED);
        }

        DocumentIndexTask task = transactionTemplate.execute(status -> {
            int updated = documentMapper.update(null,
                    Wrappers.<KnowledgeDocument>update()
                            .eq("id", document.getId())
                            .eq("status", DocumentStatus.UPLOADING.getValue())
                            .set("status", DocumentStatus.PENDING.getValue())
                            .setSql("version = version + 1"));
            if (updated == 0) {
                log.warn("CAS UPLOADING->PENDING failed for document {}", document.getId());
                compensateUploadingToDeleting(document.getId());
                throw new BusinessException(ErrorCode.DOCUMENT_UPLOAD_FAILED);
            }

            return documentTaskService.createPendingTask(document.getId(), userId);
        });

        if (task == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_UPLOAD_FAILED);
        }

        // After commit, attempt MQ publish (best effort)
        // If publish fails, Dispatcher will recover
        try {
            DocumentTaskPublisher.PublishResult publishResult = documentTaskPublisher.publishMain(task);
            if (publishResult.isSuccess()) {
                taskMapper.update(null,
                        Wrappers.<DocumentIndexTask>update()
                                .eq("id", task.getId())
                                .eq("status", DocumentTaskStatus.PENDING.getValue())
                                .set("status", DocumentTaskStatus.QUEUED.getValue())
                                .set("last_dispatched_at", LocalDateTime.now())
                                .setSql("version = version + 1"));
                task.setStatus(DocumentTaskStatus.QUEUED.getValue());
            } else {
                log.warn("MQ publish failed for task {}, will be recovered by Dispatcher. " +
                                "acked={}, returned={}, timeout={}",
                        task.getId(), publishResult.isAcked(), publishResult.isReturned(), publishResult.isTimeout());
            }
        } catch (Exception e) {
            log.warn("MQ publish exception for task {}, will be recovered by Dispatcher", task.getId(), e);
        }

        KnowledgeDocument pendingDoc = documentMapper.selectById(document.getId());
        return toUploadResponse(pendingDoc, task);
    }

    public PageResponse<DocumentResponse> listDocuments(Long workspaceId, Long knowledgeBaseId, Long userId,
                                                         int page, int size, String status) {
        workspaceAuthorizationService.requireMember(workspaceId, userId);
        knowledgeBaseService.getKnowledgeBase(workspaceId, knowledgeBaseId, userId);

        if (page < 1) {
            page = 1;
        }
        if (size < 1 || size > 100) {
            size = 20;
        }

        QueryWrapper<KnowledgeDocument> wrapper = Wrappers.query();
        wrapper.eq("knowledge_base_id", knowledgeBaseId);
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status.trim().toUpperCase());
        }
        long total = documentMapper.selectCount(wrapper);
        wrapper.orderByDesc("created_at");
        wrapper.last("LIMIT " + size + " OFFSET " + ((long) (page - 1) * size));

        List<KnowledgeDocument> documents = documentMapper.selectList(wrapper);
        return PageResponse.<DocumentResponse>builder()
                .items(documents.stream().map(this::toResponse).toList())
                .page(page)
                .size(size)
                .total(total)
                .build();
    }

    public DocumentResponse getDocument(Long workspaceId, Long knowledgeBaseId, Long documentId, Long userId) {
        workspaceAuthorizationService.requireMember(workspaceId, userId);
        knowledgeBaseService.getKnowledgeBase(workspaceId, knowledgeBaseId, userId);

        KnowledgeDocument document = documentMapper.selectById(documentId);
        if (document == null || !document.getKnowledgeBaseId().equals(knowledgeBaseId)) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        return toResponse(document);
    }

    public void deleteDocument(Long workspaceId, Long knowledgeBaseId, Long documentId, Long userId) {
        workspaceAuthorizationService.requireOwner(workspaceId, userId);
        knowledgeBaseService.getKnowledgeBase(workspaceId, knowledgeBaseId, userId);

        KnowledgeDocument document = documentMapper.selectById(documentId);
        if (document == null || !document.getKnowledgeBaseId().equals(knowledgeBaseId)) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        String docStatus = document.getStatus();
        if (DocumentStatus.UPLOADING.getValue().equals(docStatus)) {
            throw new BusinessException(ErrorCode.DOCUMENT_INVALID_STATE);
        }
        if (DocumentStatus.DELETING.getValue().equals(docStatus)) {
            if ("UPLOAD_COMPENSATION".equals(document.getCleanupReason())) {
                // Respect tombstone grace period. RecoveryScheduler handles cleanup.
                log.info("Document {} is DELETING/UPLOAD_COMPENSATION, cleanup_eligible_at={}, deferring to RecoveryScheduler",
                        documentId, document.getCleanupEligibleAt());
                return;
            }
            // USER_DELETE: process immediately
            tryDeleteObjectAndHardDelete(document, workspaceId);
            return;
        }

        // CAS to DELETING + cancel active tasks
        transactionTemplate.execute(status -> {
            int updated = documentMapper.update(null,
                    new UpdateWrapper<KnowledgeDocument>()
                            .eq("id", documentId)
                            .ne("status", DocumentStatus.DELETING.getValue())
                            .set("status", DocumentStatus.DELETING.getValue())
                            .set("cleanup_reason", "USER_DELETE")
                            .set("cleanup_eligible_at", LocalDateTime.now())
                            .setSql("version = version + 1"));

            if (updated == 0) {
                throw new BusinessException(ErrorCode.DOCUMENT_INVALID_STATE);
            }

            // Cancel active Phase 2 task
            taskMapper.update(null,
                    new UpdateWrapper<DocumentIndexTask>()
                            .eq("document_id", documentId)
                            .in("status", DocumentTaskStatus.PENDING.getValue(),
                                    DocumentTaskStatus.QUEUED.getValue(),
                                    DocumentTaskStatus.PROCESSING.getValue(),
                                    DocumentTaskStatus.RETRY_WAIT.getValue())
                            .set("status", DocumentTaskStatus.CANCELLED.getValue())
                            .setSql("version = version + 1"));

            // Phase 3: Cancel retrieval task
            retrievalTaskService.cancelTask(documentId);

            return null;
        });

        tryDeleteObjectAndHardDelete(document, workspaceId);
    }

    private void tryDeleteObjectAndHardDelete(KnowledgeDocument document, Long workspaceId) {
        // Phase 3: Create durable cleanup task BEFORE hard delete
        // The cleanup task survives document deletion (no FK) and eventually removes ES docs
        retrievalCleanupService.createCleanupTask(
                document.getId(),
                workspaceId,
                document.getKnowledgeBaseId(),
                retrievalProperties.getEsIndexName());

        // Delete MinIO object
        try {
            objectStorageService.deleteObject(document.getBucketName(), document.getObjectKey());
        } catch (StorageException e) {
            log.warn("Failed to delete MinIO object for document {}: {}", document.getId(), e.getMessage());
            throw new BusinessException(ErrorCode.DOCUMENT_DELETE_FAILED);
        }

        // Hard delete from DB (cascade removes chunks and retrieval task)
        transactionTemplate.execute(status -> {
            documentMapper.deleteById(document.getId());
            return null;
        });
    }

    public DocumentRetryResponse retryDocument(Long workspaceId, Long knowledgeBaseId, Long documentId, Long userId) {
        workspaceAuthorizationService.requireOwner(workspaceId, userId);
        knowledgeBaseService.getKnowledgeBase(workspaceId, knowledgeBaseId, userId);

        KnowledgeDocument document = documentMapper.selectById(documentId);
        if (document == null || !document.getKnowledgeBaseId().equals(knowledgeBaseId)) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        if (!DocumentStatus.FAILED.getValue().equals(document.getStatus())) {
            throw new BusinessException(ErrorCode.DOCUMENT_RETRY_NOT_ALLOWED);
        }

        // Check MinIO object still exists
        try {
            if (!objectStorageService.objectExists(document.getBucketName(), document.getObjectKey())) {
                throw new BusinessException(ErrorCode.DOCUMENT_RETRY_NOT_ALLOWED);
            }
        } catch (StorageException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_RETRY_NOT_ALLOWED);
        }

        // Check no active task
        Long activeTaskCount = taskMapper.selectCount(
                Wrappers.<DocumentIndexTask>query()
                        .eq("document_id", documentId)
                        .in("status", DocumentTaskStatus.PENDING.getValue(),
                                DocumentTaskStatus.QUEUED.getValue(),
                                DocumentTaskStatus.PROCESSING.getValue(),
                                DocumentTaskStatus.RETRY_WAIT.getValue()));
        if (activeTaskCount > 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_RETRY_NOT_ALLOWED);
        }

        DocumentIndexTask task = transactionTemplate.execute(status -> {
            // Clear failure fields and move to PENDING
            documentMapper.update(null,
                    new UpdateWrapper<KnowledgeDocument>()
                            .eq("id", documentId)
                            .eq("status", DocumentStatus.FAILED.getValue())
                            .set("status", DocumentStatus.PENDING.getValue())
                            .set("failure_code", null)
                            .set("failure_message", null)
                            .set("completed_at", null)
                            .setSql("version = version + 1"));

            // Create new task
            return documentTaskService.createPendingTask(documentId, userId);
        });

        if (task == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_RETRY_NOT_ALLOWED);
        }

        // After commit, attempt MQ publish
        try {
            DocumentTaskPublisher.PublishResult publishResult = documentTaskPublisher.publishMain(task);
            if (publishResult.isSuccess()) {
                taskMapper.update(null,
                        Wrappers.<DocumentIndexTask>update()
                                .eq("id", task.getId())
                                .eq("status", DocumentTaskStatus.PENDING.getValue())
                                .set("status", DocumentTaskStatus.QUEUED.getValue())
                                .set("last_dispatched_at", LocalDateTime.now())
                                .setSql("version = version + 1"));
                task.setStatus(DocumentTaskStatus.QUEUED.getValue());
            }
        } catch (Exception e) {
            log.warn("MQ publish failed for retry task {}", task.getId(), e);
        }

        return DocumentRetryResponse.builder()
                .documentId(documentId)
                .taskId(task.getId())
                .status(DocumentStatus.PENDING.getValue())
                .createdAt(task.getCreatedAt())
                .build();
    }

    public PageResponse<DocumentChunkResponse> listChunks(Long workspaceId, Long knowledgeBaseId,
                                                           Long documentId, Long userId,
                                                           int page, int size) {
        workspaceAuthorizationService.requireMember(workspaceId, userId);
        knowledgeBaseService.getKnowledgeBase(workspaceId, knowledgeBaseId, userId);

        KnowledgeDocument document = documentMapper.selectById(documentId);
        if (document == null || !document.getKnowledgeBaseId().equals(knowledgeBaseId)) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }

        if (!DocumentStatus.COMPLETED.getValue().equals(document.getStatus())) {
            throw new BusinessException(ErrorCode.DOCUMENT_CHUNK_NOT_AVAILABLE);
        }

        if (page < 1) page = 1;
        if (size < 1 || size > 100) size = 20;

        QueryWrapper<DocumentChunk> countWrapper = Wrappers.query();
        countWrapper.eq("document_id", documentId);
        long total = chunkMapper.selectCount(countWrapper);

        QueryWrapper<DocumentChunk> wrapper = Wrappers.query();
        wrapper.eq("document_id", documentId)
                .orderByAsc("chunk_index");
        wrapper.last("LIMIT " + size + " OFFSET " + ((long) (page - 1) * size));

        List<DocumentChunk> chunks = chunkMapper.selectList(wrapper);
        return PageResponse.<DocumentChunkResponse>builder()
                .items(chunks.stream().map(this::toChunkResponse).toList())
                .page(page)
                .size(size)
                .total(total)
                .build();
    }

    private DocumentChunkResponse toChunkResponse(DocumentChunk chunk) {
        return DocumentChunkResponse.builder()
                .id(chunk.getId())
                .documentId(chunk.getDocumentId())
                .chunkIndex(chunk.getChunkIndex())
                .content(chunk.getContent())
                .characterCount(chunk.getCharacterCount())
                .tokenCount(chunk.getTokenCount())
                .pageStart(chunk.getPageStart())
                .pageEnd(chunk.getPageEnd())
                .sectionPath(chunk.getSectionPath())
                .sourceMetadata(fromJson(chunk.getSourceMetadata()))
                .createdAt(chunk.getCreatedAt())
                .build();
    }

    private void checkDuplicate(Long knowledgeBaseId, String checksum) {
        QueryWrapper<KnowledgeDocument> wrapper = Wrappers.query();
        wrapper.eq("knowledge_base_id", knowledgeBaseId)
                .eq("checksum_sha256", checksum);
        if (documentMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_DUPLICATE);
        }
    }

    private void compensateUploadingToDeleting(Long documentId) {
        try {
            transactionTemplate.execute(status -> {
                int updated = documentMapper.update(null,
                        Wrappers.<KnowledgeDocument>update()
                                .eq("id", documentId)
                                .eq("status", DocumentStatus.UPLOADING.getValue())
                                .set("status", DocumentStatus.DELETING.getValue())
                                .set("cleanup_reason", "UPLOAD_COMPENSATION")
                                .set("cleanup_eligible_at",
                                        LocalDateTime.now().plusSeconds(ingestionProperties.getUploadCleanupGraceSeconds()))
                                .set("failure_code", "UPLOAD_FAILED")
                                .set("failure_message", "Upload failed and entered compensation cleanup")
                                .setSql("version = version + 1"));
                if (updated == 0) {
                    log.warn("CAS UPLOADING->DELETING compensation failed for document {}, possibly already moved", documentId);
                }
                return null;
            });
        } catch (Exception e) {
            log.error("Failed to compensate document {} to DELETING", documentId, e);
        }
    }

    private String generateObjectKey(Long workspaceId, Long knowledgeBaseId, Long documentId, DocumentFormat format) {
        return String.format("workspaces/%d/knowledge-bases/%d/documents/%d/source.%s",
                workspaceId, knowledgeBaseId, documentId, format.getExtension());
    }

    private String calculateChecksum(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private String resolveContentType(DocumentFormat format) {
        return switch (format) {
            case PDF -> "application/pdf";
            case MARKDOWN -> "text/markdown";
            case TEXT -> "text/plain";
        };
    }

    private String normalizeFileName(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "unknown";
        }
        String name = filename.trim();
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize parser metadata", e);
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize parser metadata: {}", json, e);
            return Collections.emptyMap();
        }
    }

    private DocumentUploadResponse toUploadResponse(KnowledgeDocument document, DocumentIndexTask task) {
        return DocumentUploadResponse.builder()
                .documentId(document.getId())
                .taskId(task.getId())
                .fileName(document.getOriginalFileName())
                .fileSize(document.getFileSize())
                .checksumSha256(document.getChecksumSha256())
                .status(document.getStatus())
                .createdAt(document.getCreatedAt())
                .build();
    }

    private DocumentResponse toResponse(KnowledgeDocument document) {
        DocumentTaskResponse latestTask = null;
        if (document.getId() != null) {
            List<DocumentIndexTask> tasks = taskMapper.selectList(
                    Wrappers.<DocumentIndexTask>query()
                            .eq("document_id", document.getId())
                            .orderByDesc("created_at")
                            .last("LIMIT 1"));
            if (!tasks.isEmpty()) {
                latestTask = toTaskResponse(tasks.get(0));
            }
        }

        return DocumentResponse.builder()
                .id(document.getId())
                .knowledgeBaseId(document.getKnowledgeBaseId())
                .fileName(document.getOriginalFileName())
                .fileExtension(document.getFileExtension())
                .contentType(document.getContentType())
                .fileSize(document.getFileSize())
                .checksumSha256(document.getChecksumSha256())
                .status(document.getStatus())
                .chunkStrategy(document.getChunkStrategy())
                .chunkSize(document.getChunkSize())
                .chunkOverlap(document.getChunkOverlap())
                .parserMetadata(fromJson(document.getParserMetadata()))
                .failureCode(document.getFailureCode())
                .failureMessage(document.getFailureMessage())
                .latestTask(latestTask)
                .createdBy(document.getCreatedBy())
                .completedAt(document.getCompletedAt())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }

    private DocumentTaskResponse toTaskResponse(DocumentIndexTask task) {
        return DocumentTaskResponse.builder()
                .id(task.getId())
                .status(task.getStatus())
                .attemptCount(task.getAttemptCount())
                .maxAttempts(task.getMaxAttempts())
                .lastErrorCode(task.getLastErrorCode())
                .lastErrorMessage(task.getLastErrorMessage())
                .createdAt(task.getCreatedAt())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .build();
    }
}
