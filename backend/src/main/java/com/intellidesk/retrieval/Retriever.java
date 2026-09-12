package com.intellidesk.retrieval;

import java.util.List;

/**
 * Unified retriever contract.
 * Retriever only accepts server-generated RetrievalScope.
 */
public interface Retriever {
    RetrievalSource source();
    List<RetrievalResult> retrieve(RetrievalQuery query, RetrievalScope scope, int topK);
}