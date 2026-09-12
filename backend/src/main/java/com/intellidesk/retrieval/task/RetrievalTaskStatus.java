package com.intellidesk.retrieval.task;

import lombok.Getter;

@Getter
public enum RetrievalTaskStatus {
    PENDING("PENDING"),
    QUEUED("QUEUED"),
    PROCESSING("PROCESSING"),
    RETRY_WAIT("RETRY_WAIT"),
    READY("READY"),
    FAILED("FAILED"),
    CANCELLED("CANCELLED");

    private final String value;

    RetrievalTaskStatus(String value) {
        this.value = value;
    }
}