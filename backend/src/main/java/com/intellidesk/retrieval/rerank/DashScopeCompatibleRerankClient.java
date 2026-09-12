package com.intellidesk.retrieval.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * DashScope-compatible rerank HTTP client.
 */
@Slf4j
public class DashScopeCompatibleRerankClient implements RerankClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public DashScopeCompatibleRerankClient(String baseUrl, String apiKey, String model, Duration timeout) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
    }

    @Override
    public List<RerankResponse.RerankResult> rerank(String query, List<String> documents, int topK) {
        try {
            RerankRequest request = new RerankRequest();
            request.setModel(model);
            RerankRequest.RerankInput input = new RerankRequest.RerankInput();
            input.setQuery(query);
            input.setDocuments(documents);
            request.setInput(input);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<RerankRequest> entity = new HttpEntity<>(request, headers);
            RerankResponse response = restTemplate.postForObject(
                    URI.create(baseUrl), entity, RerankResponse.class);

            if (response == null || response.getResults() == null) {
                throw new RuntimeException("Empty rerank response");
            }

            // Validate indices
            for (RerankResponse.RerankResult result : response.getResults()) {
                if (result.getIndex() < 0 || result.getIndex() >= documents.size()) {
                    throw new RuntimeException("Rerank result index out of range: " + result.getIndex());
                }
                if (Double.isNaN(result.getRelevanceScore()) || Double.isInfinite(result.getRelevanceScore())) {
                    throw new RuntimeException("Rerank result score is NaN or Infinity");
                }
            }

            // Check for duplicate indices
            long distinctIndices = response.getResults().stream()
                    .map(RerankResponse.RerankResult::getIndex)
                    .distinct().count();
            if (distinctIndices != response.getResults().size()) {
                throw new RuntimeException("Duplicate rerank result indices");
            }

            log.debug("Rerank: {} documents -> {} results", documents.size(), response.getResults().size());
            return response.getResults().stream().limit(topK).toList();
        } catch (Exception e) {
            log.error("Rerank failed for query: {}", query.substring(0, Math.min(50, query.length())), e);
            throw new RuntimeException("Rerank service unavailable: " + sanitize(e.getMessage()));
        }
    }

    private String sanitize(String message) {
        if (message == null) return "unknown error";
        return message.replaceAll("Bearer\\s+[^\\s]+", "Bearer ***")
                .replaceAll("sk-[a-zA-Z0-9]+", "sk-***");
    }
}