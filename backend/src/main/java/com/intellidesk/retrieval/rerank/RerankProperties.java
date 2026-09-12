package com.intellidesk.retrieval.rerank;

import lombok.Data;

@Data
public class RerankProperties {
    private boolean enabled = false;
    private String baseUrl;
    private String apiKey;
    private String model = "gte-rerank";
    private int timeoutSeconds = 15;
}