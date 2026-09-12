package com.intellidesk.document.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class DocumentUploadResponse {

    private final Long documentId;
    private final Long taskId;
    private final String fileName;
    private final Long fileSize;
    private final String checksumSha256;
    private final String status;
    private final LocalDateTime createdAt;
}
