package com.intellidesk.retrieval.rerank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RerankRequest {
    private String model;
    private RerankInput input;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RerankInput {
        private String query;
        private List<String> documents;
    }
}