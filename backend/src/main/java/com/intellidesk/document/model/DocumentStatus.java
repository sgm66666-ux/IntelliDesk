package com.intellidesk.document.model;

import lombok.Getter;

@Getter
public enum DocumentStatus {
    UPLOADING("UPLOADING"),
    PENDING("PENDING"),
    PROCESSING("PROCESSING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    DELETING("DELETING");

    private final String value;

    DocumentStatus(String value) {
        this.value = value;
    }
}
