package com.intellidesk.embedding;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class EmbeddingBatchResult {

    private List<float[]> embeddings;
    private String model;
    private int dimension;
    private int totalTokens;

    public int size() {
        return embeddings.size();
    }
}