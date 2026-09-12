package com.intellidesk.chat.context;

import com.intellidesk.retrieval.ScoreType;

/**
 * A single context entry in the RAG context.
 * Immutable record — citation IDs are assigned by RagContextBuilder.
 */
public record ContextEntry(
        int citationId,
        Long chunkId,
        Long documentId,
        String documentName,
        String content,
        int chunkIndex,
        String sectionPath,
        float score,
        ScoreType scoreType,
        Integer pageNumber
) {
    public ContextEntry {
        if (citationId <= 0) {
            throw new IllegalArgumentException("citationId must be positive");
        }
        if (chunkId == null) {
            throw new IllegalArgumentException("chunkId must not be null");
        }
        if (documentId == null) {
            throw new IllegalArgumentException("documentId must not be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
    }
}