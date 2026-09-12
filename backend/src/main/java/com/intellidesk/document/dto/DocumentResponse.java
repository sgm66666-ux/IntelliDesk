package com.intellidesk.document.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
@Builder
public class DocumentResponse {

    private final Long id;
    private final Long knowledgeBaseId;
    private final String fileName;
    private final String fileExtension;
    private final String contentType;
    private final Long fileSize;
    private final String checksumSha256;
    private final String status;
    private final String chunkStrategy;
    private final Integer chunkSize;
    private final Integer chunkOverlap;
    private final Map<String, Object> parserMetadata;
    private final String failureCode;
    private final String failureMessage;
    private final DocumentTaskResponse latestTask;
    private final Long createdBy;
    private final LocalDateTime completedAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
