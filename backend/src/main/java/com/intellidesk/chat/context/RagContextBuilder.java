package com.intellidesk.chat.context;

import com.intellidesk.retrieval.RetrievalResult;

import java.util.List;

/**
 * Builds RAG context from retrieval results.
 * Handles: dedup, token budget, citation ID assignment, metadata hydration, truncation.
 */
public interface RagContextBuilder {

    /**
     * Build RAG context from retrieval results.
     * @param retrievalResults retrieval results in authoritative order (from Phase 3)
     * @param tokenBudget maximum allowed estimated tokens
     * @return assembled RagContext with citation IDs and truncated content
     */
    RagContext build(List<RetrievalResult> retrievalResults, int tokenBudget);
}