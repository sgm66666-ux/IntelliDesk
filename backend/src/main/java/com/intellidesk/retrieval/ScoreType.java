package com.intellidesk.retrieval;

import lombok.Getter;

@Getter
public enum ScoreType {
    COSINE_SIMILARITY("cosine_similarity"),
    BM25("bm25"),
    RRF("rrf"),
    RERANKED("reranked");

    private final String value;

    ScoreType(String value) {
        this.value = value;
    }
}