package com.intellidesk.retrieval.indexing.mq;

import java.io.Serializable;
import java.time.LocalDateTime;

public record RetrievalIndexMessage(
        int schemaVersion,
        String messageId,
        long taskId,
        long documentId,
        int generation,
        LocalDateTime occurredAt
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public RetrievalIndexMessage {
        if (schemaVersion == 0) {
            schemaVersion = 1;
        }
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
    }

    public RetrievalIndexMessage() {
        this(1, null, 0, 0, 0, LocalDateTime.now());
    }

    public static RetrievalIndexMessage create(String messageId, long taskId, long documentId, int generation) {
        return new RetrievalIndexMessage(1, messageId, taskId, documentId, generation, LocalDateTime.now());
    }
}