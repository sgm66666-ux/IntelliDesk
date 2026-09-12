package com.intellidesk.chat.citation;

/**
 * A citation entry derived from a RagContext ContextEntry.
 * Immutable record — server-authoritative, never from LLM.
 */
public record Citation(
        int citationId,
        Long documentId,
        String documentName,
        Long chunkId,
        String content,
        float score,
        Integer pageNumber
) {
    public Citation {
        if (citationId <= 0) {
            throw new IllegalArgumentException("citationId must be positive");
        }
        if (documentId == null) {
            throw new IllegalArgumentException("documentId must not be null");
        }
        if (chunkId == null) {
            throw new IllegalArgumentException("chunkId must not be null");
        }
    }
}