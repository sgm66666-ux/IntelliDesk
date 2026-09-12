package com.intellidesk.embedding;

import java.util.List;

/**
 * Embedding service abstraction.
 * Business layer depends only on this interface, not on Spring AI directly.
 */
public interface EmbeddingService {

    /**
     * Embed a single query text.
     */
    float[] embedQuery(String text);

    /**
     * Embed a batch of document texts.
     * Preserves input order. Returns embeddings in the same order.
     */
    EmbeddingBatchResult embedDocuments(List<String> texts);

    /**
     * Returns the dimension of the embedding vectors produced by this service.
     */
    int dimension();

    /**
     * Returns the model name in use.
     */
    String model();
}