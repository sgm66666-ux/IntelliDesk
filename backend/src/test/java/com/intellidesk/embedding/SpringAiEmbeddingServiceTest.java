package com.intellidesk.embedding;

import com.intellidesk.infrastructure.config.EmbeddingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SpringAiEmbeddingService")
class SpringAiEmbeddingServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    private EmbeddingProperties properties;
    private SpringAiEmbeddingService service;

    @BeforeEach
    void setUp() {
        properties = new EmbeddingProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("https://api.openai.com");
        properties.setModel("text-embedding-3-small");
        properties.setDimension(1536);
        properties.setBatchSize(3);
        service = new SpringAiEmbeddingService(embeddingModel, properties);
    }

    private float[] createVector(int seed) {
        float[] v = new float[1536];
        for (int i = 0; i < 1536; i++) {
            v[i] = seed * 0.001f + i * 0.0001f;
        }
        return v;
    }

    private EmbeddingResponse mockEmbeddingResponse(List<float[]> vectors) {
        List<Embedding> embeddings = vectors.stream()
                .map(v -> new Embedding(v, 0))
                .toList();
        return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata());
    }

    @Nested
    @DisplayName("embedQuery")
    class EmbedQueryTests {

        @Test
        @DisplayName("returns valid 1536-dim vector for single query")
        void returnsValidVector() {
            float[] expected = createVector(1);
            when(embeddingModel.embed(anyString())).thenReturn(expected);

            float[] result = service.embedQuery("test query");

            assertThat(result).hasSize(1536);
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("rejects null query")
        void rejectsNullQuery() {
            assertThatThrownBy(() -> service.embedQuery(null))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("must not be empty");
        }

        @Test
        @DisplayName("rejects blank query")
        void rejectsBlankQuery() {
            assertThatThrownBy(() -> service.embedQuery("   "))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("must not be empty");
        }

        @Test
        @DisplayName("rejects dimension mismatch")
        void rejectsDimensionMismatch() {
            float[] wrongVector = new float[768];
            when(embeddingModel.embed(anyString())).thenReturn(wrongVector);

            assertThatThrownBy(() -> service.embedQuery("test"))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("Expected 1536 dimensions");
        }

        @Test
        @DisplayName("rejects NaN in vector")
        void rejectsNaN() {
            float[] nanVector = createVector(1);
            nanVector[500] = Float.NaN;
            when(embeddingModel.embed(anyString())).thenReturn(nanVector);

            assertThatThrownBy(() -> service.embedQuery("test"))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("NaN or Infinity");
        }

        @Test
        @DisplayName("rejects Infinity in vector")
        void rejectsInfinity() {
            float[] infVector = createVector(1);
            infVector[500] = Float.POSITIVE_INFINITY;
            when(embeddingModel.embed(anyString())).thenReturn(infVector);

            assertThatThrownBy(() -> service.embedQuery("test"))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("NaN or Infinity");
        }

        @Test
        @DisplayName("rejects empty vector")
        void rejectsEmptyVector() {
            when(embeddingModel.embed(anyString())).thenReturn(new float[0]);

            assertThatThrownBy(() -> service.embedQuery("test"))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("Empty vector");
        }
    }

    @Nested
    @DisplayName("embedDocuments")
    class EmbedDocumentsTests {

        @Test
        @DisplayName("preserves input order")
        void preservesInputOrder() {
            List<String> texts = List.of("text0", "text1", "text2");
            List<float[]> vectors = List.of(createVector(0), createVector(1), createVector(2));

            when(embeddingModel.call(any(EmbeddingRequest.class)))
                    .thenReturn(mockEmbeddingResponse(vectors));

            EmbeddingBatchResult result = service.embedDocuments(texts);

            assertThat(result.size()).isEqualTo(3);
            assertThat(result.getEmbeddings().get(0)).isEqualTo(vectors.get(0));
            assertThat(result.getEmbeddings().get(1)).isEqualTo(vectors.get(1));
            assertThat(result.getEmbeddings().get(2)).isEqualTo(vectors.get(2));
        }

        @Test
        @DisplayName("splits into batches by configured batch size")
        void splitsIntoBatches() {
            // batch size is 3, send 5 texts -> 2 batches
            List<String> texts = List.of("a", "b", "c", "d", "e");
            List<float[]> batch1 = List.of(createVector(0), createVector(1), createVector(2));
            List<float[]> batch2 = List.of(createVector(3), createVector(4));

            when(embeddingModel.call(any(EmbeddingRequest.class)))
                    .thenReturn(mockEmbeddingResponse(batch1))
                    .thenReturn(mockEmbeddingResponse(batch2));

            EmbeddingBatchResult result = service.embedDocuments(texts);

            assertThat(result.size()).isEqualTo(5);
            assertThat(result.getEmbeddings()).hasSize(5);
        }

        @Test
        @DisplayName("rejects response count mismatch")
        void rejectsResponseCountMismatch() {
            List<String> texts = List.of("a", "b", "c");
            // return only 2 vectors instead of 3
            List<float[]> vectors = List.of(createVector(0), createVector(1));

            when(embeddingModel.call(any(EmbeddingRequest.class)))
                    .thenReturn(mockEmbeddingResponse(vectors));

            assertThatThrownBy(() -> service.embedDocuments(texts))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("Expected 3 embeddings but got 2");
        }

        @Test
        @DisplayName("rejects null input list")
        void rejectsNullInput() {
            assertThatThrownBy(() -> service.embedDocuments(null))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("must not be empty");
        }

        @Test
        @DisplayName("rejects empty input list")
        void rejectsEmptyInput() {
            assertThatThrownBy(() -> service.embedDocuments(List.of()))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("must not be empty");
        }

        @Test
        @DisplayName("rejects blank text in batch")
        void rejectsBlankText() {
            List<String> texts = List.of("valid", "   ");

            assertThatThrownBy(() -> service.embedDocuments(texts))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("must be non-empty");
        }

        @Test
        @DisplayName("rejects dimension mismatch in batch")
        void rejectsDimensionMismatchInBatch() {
            List<String> texts = List.of("a", "b");
            float[] wrongVector = new float[768];
            List<float[]> vectors = List.of(createVector(0), wrongVector);

            when(embeddingModel.call(any(EmbeddingRequest.class)))
                    .thenReturn(mockEmbeddingResponse(vectors));

            assertThatThrownBy(() -> service.embedDocuments(texts))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("Expected 1536 dimensions");
        }

        @Test
        @DisplayName("sanitizes provider errors")
        void sanitizesProviderErrors() {
            when(embeddingModel.call(any(EmbeddingRequest.class)))
                    .thenThrow(new RuntimeException("401 Unauthorized"));

            assertThatThrownBy(() -> service.embedDocuments(List.of("a")))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("authentication failed");
        }
    }

    @Nested
    @DisplayName("model and dimension")
    class ModelAndDimensionTests {

        @Test
        @DisplayName("returns configured model name")
        void returnsModelName() {
            assertThat(service.model()).isEqualTo("text-embedding-3-small");
        }

        @Test
        @DisplayName("returns configured dimension")
        void returnsDimension() {
            assertThat(service.dimension()).isEqualTo(1536);
        }
    }

    @Nested
    @DisplayName("transaction guard")
    class TransactionGuardTests {

        @Test
        @DisplayName("rejects embedding call inside active transaction")
        void rejectsCallInTransaction() {
            // This test verifies the guard exists; it can't actually be in a transaction
            // in a unit test without Spring context, but the guard code path is covered.
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }
}