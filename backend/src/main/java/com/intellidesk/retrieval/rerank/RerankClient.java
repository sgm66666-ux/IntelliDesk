package com.intellidesk.retrieval.rerank;

import java.util.List;

/**
 * Provider-neutral rerank client interface.
 */
public interface RerankClient {
    List<RerankResponse.RerankResult> rerank(String query, List<String> documents, int topK);
}