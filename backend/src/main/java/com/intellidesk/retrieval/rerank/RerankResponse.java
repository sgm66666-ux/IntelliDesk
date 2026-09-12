package com.intellidesk.retrieval.rerank;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RerankResponse {
    private List<RerankResult> results;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RerankResult {
        private int index;
        @JsonProperty("relevance_score")
        private double relevanceScore;
    }
}