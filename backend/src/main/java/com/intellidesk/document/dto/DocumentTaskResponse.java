package com.intellidesk.document.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class DocumentTaskResponse {

    private final Long id;
    private final String status;
    private final Integer attemptCount;
    private final Integer maxAttempts;
    private final String lastErrorCode;
    private final String lastErrorMessage;
    private final LocalDateTime createdAt;
    private final LocalDateTime startedAt;
    private final LocalDateTime completedAt;
}
