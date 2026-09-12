package com.intellidesk.document.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class DocumentRetryResponse {

    private final Long documentId;
    private final Long taskId;
    private final String status;
    private final LocalDateTime createdAt;
}