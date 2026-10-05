package com.intellidesk.document.model;

import lombok.Getter;
import java.util.Arrays;
import java.util.List;

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

    public boolean canTransitionTo(DocumentTaskStatus target) {
        if (target == null || target == this) return false;
        return switch (this) {
            case PENDING -> target == QUEUED || target == PROCESSING || target == CANCELLED;
            case QUEUED -> target == PROCESSING || target == CANCELLED;
            case RETRY_WAIT -> target == QUEUED || target == PROCESSING || target == CANCELLED;
            case PROCESSING -> target == SUCCEEDED || target == RETRY_WAIT || target == DEAD || target == CANCELLED;
            case SUCCEEDED, DEAD, CANCELLED -> false;
        };
    }

    public static List<String> claimableValues() {
        return Arrays.stream(values()).filter(value -> value.canTransitionTo(PROCESSING))
                .map(DocumentTaskStatus::getValue).toList();
    }
}
