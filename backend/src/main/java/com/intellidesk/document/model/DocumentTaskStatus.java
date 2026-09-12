package com.intellidesk.document.model;

import lombok.Getter;

@Getter
public enum DocumentTaskStatus {
    PENDING("PENDING"),
    QUEUED("QUEUED"),
    PROCESSING("PROCESSING"),
    RETRY_WAIT("RETRY_WAIT"),
    SUCCEEDED("SUCCEEDED"),
    DEAD("DEAD"),
    CANCELLED("CANCELLED");

    private final String value;

    DocumentTaskStatus(String value) {
        this.value = value;
    }
}
