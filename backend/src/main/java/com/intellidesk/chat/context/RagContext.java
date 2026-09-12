package com.intellidesk.chat.context;

import java.util.List;

/**
 * Assembled RAG context with citation-aware entries.
 * Immutable — entries are copied on construction.
 */
public record RagContext(
        List<ContextEntry> entries,
        int estimatedTokens,
        int tokenBudget
) {
    public RagContext {
        entries = List.copyOf(entries);
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens must be >= 0");
        }
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be > 0");
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Format context as prompt-ready text for LLM.
     * Each entry is formatted with citation ID, source, section, and content.
     */
    public String toPromptText() {
        if (entries.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ContextEntry entry : entries) {
            sb.append("[").append(entry.citationId()).append("]\n");
            sb.append("Source: ").append(entry.documentName() != null ? entry.documentName() : "Unknown").append("\n");
            sb.append("Chunk: ").append(entry.chunkId()).append("\n");
            if (entry.sectionPath() != null && !entry.sectionPath().isEmpty()) {
                sb.append("Section: ").append(entry.sectionPath()).append("\n");
            }
            if (entry.pageNumber() != null) {
                sb.append("Page: ").append(entry.pageNumber()).append("\n");
            }
            sb.append("Content:\n").append(entry.content()).append("\n\n");
        }
        return sb.toString().trim();
    }
}