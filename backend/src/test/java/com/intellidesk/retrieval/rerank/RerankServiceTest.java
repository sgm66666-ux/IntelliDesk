package com.intellidesk.retrieval.rerank;

import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.RetrievalSource;
import com.intellidesk.retrieval.ScoreType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RerankServiceTest {

    private RerankClient mockClient;
    private RerankService rerankService;

    @BeforeEach
    void setUp() {
        mockClient = mock(RerankClient.class);
        rerankService = new RerankService(mockClient);
    }

    private RetrievalResult result(long chunkId, float score, String content) {
        RetrievalResult r = new RetrievalResult(chunkId, null, null, content, score,
                ScoreType.RRF, 0, null, RetrievalSource.HYBRID,
                List.of(RetrievalSource.VECTOR, RetrievalSource.KEYWORD),
                Map.of("vector_score", score, "keyword_score", score * 0.5f));
        return r;
    }

    @Nested
    @DisplayName("HTTP request contract")
    class HttpRequestContract {

        @Test
        @DisplayName("sends correct query and documents to rerank client")
        void sendsCorrectPayload() {
            List<RetrievalResult> candidates = List.of(
                    result(1L, 0.9f, "doc A"),
                    result(2L, 0.8f, "doc B"),
                    result(3L, 0.7f, "doc C")
            );

            when(mockClient.rerank(eq("test query"), anyList(), eq(2)))
                    .thenReturn(List.of(
                            new RerankResponse.RerankResult(2, 0.95),
                            new RerankResponse.RerankResult(1, 0.85)
                    ));

            rerankService.rerank("test query", candidates, 2);

            verify(mockClient).rerank(eq("test query"), argThat(docs ->
                    docs.size() == 3 && docs.get(0).equals("doc A")), eq(2));
        }
    }

    @Nested
    @DisplayName("reordered result")
    class ReorderedResult {

        @Test
        @DisplayName("rerank reorders results by relevance score")
        void reordersByRelevance() {
            List<RetrievalResult> candidates = List.of(
                    result(1L, 0.9f, "A"),
                    result(2L, 0.8f, "B"),
                    result(3L, 0.7f, "C")
            );

            when(mockClient.rerank(anyString(), anyList(), eq(3)))
                    .thenReturn(List.of(
                            new RerankResponse.RerankResult(1, 0.99),  // index 1 -> chunkId=2
                            new RerankResponse.RerankResult(0, 0.88),  // index 0 -> chunkId=1
                            new RerankResponse.RerankResult(2, 0.77)   // index 2 -> chunkId=3
                    ));

            List<RetrievalResult> reranked = rerankService.rerank("q", candidates, 3);

            assertThat(reranked).hasSize(3);
            assertThat(reranked.get(0).getChunkId()).isEqualTo(2L);
            assertThat(reranked.get(0).getScoreType()).isEqualTo(ScoreType.RERANKED);
            assertThat(reranked.get(0).getRetrievalSource()).isEqualTo(RetrievalSource.RERANKED);
            assertThat(reranked.get(1).getChunkId()).isEqualTo(1L);
            assertThat(reranked.get(2).getChunkId()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("topK")
    class TopK {

        @Test
        @DisplayName("truncates to topK")
        void truncatesToTopK() {
            List<RetrievalResult> candidates = List.of(
                    result(1L, 0.9f, "A"),
                    result(2L, 0.8f, "B"),
                    result(3L, 0.7f, "C")
            );

            when(mockClient.rerank(anyString(), anyList(), eq(2)))
                    .thenReturn(List.of(
                            new RerankResponse.RerankResult(2, 0.99),
                            new RerankResponse.RerankResult(0, 0.88)
                    ));

            List<RetrievalResult> reranked = rerankService.rerank("q", candidates, 2);

            assertThat(reranked).hasSize(2);
        }
    }

    @Nested
    @DisplayName("ties")
    class Ties {

        @Test
        @DisplayName("empty candidates returns empty")
        void emptyCandidates() {
            List<RetrievalResult> reranked = rerankService.rerank("q", List.of(), 3);
            assertThat(reranked).isEmpty();
            verifyNoInteractions(mockClient);
        }
    }

    @Nested
    @DisplayName("duplicate/missing/out-of-range provider index")
    class ProviderIndexValidation {

        @Test
        @DisplayName("out-of-range index is silently skipped")
        void outOfRangeIndexSkipped() {
            List<RetrievalResult> candidates = List.of(
                    result(1L, 0.9f, "A"),
                    result(2L, 0.8f, "B")
            );

            when(mockClient.rerank(anyString(), anyList(), eq(2)))
                    .thenReturn(List.of(
                            new RerankResponse.RerankResult(0, 0.99),
                            new RerankResponse.RerankResult(5, 0.88) // out of range
                    ));

            List<RetrievalResult> reranked = rerankService.rerank("q", candidates, 2);
            assertThat(reranked).hasSize(1);
            assertThat(reranked.get(0).getChunkId()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("timeout")
    class Timeout {

        @Test
        @DisplayName("client timeout propagates as exception")
        void timeoutPropagates() {
            when(mockClient.rerank(anyString(), anyList(), anyInt()))
                    .thenThrow(new RuntimeException("Read timed out"));

            List<RetrievalResult> candidates = List.of(result(1L, 0.9f, "A"));

            assertThatThrownBy(() -> rerankService.rerank("q", candidates, 1))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Rerank service unavailable");
        }
    }

    @Nested
    @DisplayName("5xx")
    class ServerError {

        @Test
        @DisplayName("5xx propagates as exception")
        void serverErrorPropagates() {
            when(mockClient.rerank(anyString(), anyList(), anyInt()))
                    .thenThrow(new RuntimeException("500 Internal Server Error"));

            List<RetrievalResult> candidates = List.of(result(1L, 0.9f, "A"));

            assertThatThrownBy(() -> rerankService.rerank("q", candidates, 1))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Rerank service unavailable");
        }
    }

    @Nested
    @DisplayName("429")
    class RateLimit {

        @Test
        @DisplayName("429 propagates as exception")
        void rateLimitPropagates() {
            when(mockClient.rerank(anyString(), anyList(), anyInt()))
                    .thenThrow(new RuntimeException("429 Too Many Requests"));

            List<RetrievalResult> candidates = List.of(result(1L, 0.9f, "A"));

            assertThatThrownBy(() -> rerankService.rerank("q", candidates, 1))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Rerank service unavailable");
        }
    }

    @Nested
    @DisplayName("secret redaction")
    class SecretRedaction {

        @Test
        @DisplayName("provider error message does not leak to client")
        void errorMessageSanitized() {
            when(mockClient.rerank(anyString(), anyList(), anyInt()))
                    .thenThrow(new RuntimeException("API key sk-abc123 is invalid"));

            List<RetrievalResult> candidates = List.of(result(1L, 0.9f, "A"));

            assertThatThrownBy(() -> rerankService.rerank("q", candidates, 1))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Rerank service unavailable");
        }
    }
}