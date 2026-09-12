package com.intellidesk.embedding;

import com.intellidesk.infrastructure.config.EmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

@Service
public class SpringAiEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiEmbeddingService.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingProperties properties;

    public SpringAiEmbeddingService(EmbeddingModel embeddingModel, EmbeddingProperties properties) {
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    @Override
    public float[] embedQuery(String text) {
        assertNotInTransaction();
        if (text == null || text.isBlank()) {
            throw new EmbeddingException("EMBEDDING_EMPTY_INPUT", "Query text must not be empty");
        }
        try {
            float[] vector = embeddingModel.embed(text);
            validateVector(vector, "query");
            return vector;
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw sanitizeError(e);
        }
    }

    @Override
    public EmbeddingBatchResult embedDocuments(List<String> texts) {
        assertNotInTransaction();
        if (texts == null || texts.isEmpty()) {
            throw new EmbeddingException("EMBEDDING_EMPTY_INPUT", "Document texts must not be empty");
        }
        if (texts.stream().anyMatch(t -> t == null || t.isBlank())) {
            throw new EmbeddingException("EMBEDDING_EMPTY_INPUT", "All document texts must be non-empty");
        }

        int batchSize = properties.getBatchSize();
        List<float[]> allVectors = new ArrayList<>();
        int totalTokens = 0;

        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);

            try {
                EmbeddingRequest request = new EmbeddingRequest(batch, null);
                EmbeddingResponse response = embeddingModel.call(request);

                List<float[]> batchVectors = response.getResults().stream()
                        .map(r -> r.getOutput())
                        .toList();

                if (batchVectors.size() != batch.size()) {
                    throw new EmbeddingException(
                            "EMBEDDING_COUNT_MISMATCH",
                            String.format("Expected %d embeddings but got %d", batch.size(), batchVectors.size())
                    );
                }

                for (int j = 0; j < batchVectors.size(); j++) {
                    validateVector(batchVectors.get(j), "document batch[" + (i + j) + "]");
                }

                allVectors.addAll(batchVectors);
            } catch (EmbeddingException e) {
                throw e;
            } catch (Exception e) {
                throw sanitizeError(e);
            }
        }

        return new EmbeddingBatchResult(allVectors, properties.getModel(), properties.getDimension(), totalTokens);
    }

    @Override
    public int dimension() {
        return properties.getDimension();
    }

    @Override
    public String model() {
        return properties.getModel();
    }

    private void validateVector(float[] vector, String label) {
        if (vector == null || vector.length == 0) {
            throw new EmbeddingException("EMBEDDING_INVALID_OUTPUT",
                    String.format("Empty vector for %s", label));
        }
        if (vector.length != properties.getDimension()) {
            throw new EmbeddingException("EMBEDDING_DIMENSION_MISMATCH",
                    String.format("Expected %d dimensions but got %d for %s",
                            properties.getDimension(), vector.length, label));
        }
        for (int i = 0; i < vector.length; i++) {
            if (Float.isNaN(vector[i]) || Float.isInfinite(vector[i])) {
                throw new EmbeddingException("EMBEDDING_INVALID_OUTPUT",
                        String.format("Vector contains NaN or Infinity at index %d for %s", i, label));
            }
        }
    }

    private EmbeddingException sanitizeError(Exception e) {
        log.error("Embedding provider error", e);
        String message = e.getMessage() != null ? e.getMessage() : "Unknown embedding error";
        // Strip any API key or sensitive data from error messages
        if (message.contains("401") || message.contains("Unauthorized")) {
            return new EmbeddingException("EMBEDDING_AUTH_ERROR", "Embedding authentication failed");
        }
        if (message.contains("429") || message.contains("rate")) {
            return new EmbeddingException("EMBEDDING_RATE_LIMIT", "Embedding rate limit exceeded");
        }
        return new EmbeddingException("EMBEDDING_PROVIDER_ERROR", message);
    }

    private void assertNotInTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new EmbeddingException(
                    "EMBEDDING_TX_VIOLATION",
                    "External embedding provider must not be called within a database transaction"
            );
        }
    }
}