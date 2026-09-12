package com.intellidesk.retrieval.fusion;

import com.intellidesk.common.BusinessException;
import com.intellidesk.retrieval.*;
import com.intellidesk.retrieval.keyword.KeywordResult;
import com.intellidesk.retrieval.keyword.KeywordRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("HybridRetriever")
class HybridRetrieverTest {

    @Mock
    private VectorRetriever vectorRetriever;

    @Mock
    private KeywordRetriever keywordRetriever;

    private HybridRetriever hybridRetriever;

    private RetrievalScope scope = RetrievalScope.of(1L, List.of(1L));
    private RetrievalQuery query = new RetrievalQuery("test query", scope);

    private RetrievalResult vectorResult(long chunkId, float score) {
        return new RetrievalResult(chunkId, null, null, "content-" + chunkId, score,
                ScoreType.COSINE_SIMILARITY, 0, null);
    }

    private KeywordResult keywordResult(long chunkId, double score) {
        return new KeywordResult(chunkId, score);
    }

    @BeforeEach
    void setUp() {
        hybridRetriever = new HybridRetriever(vectorRetriever, keywordRetriever);
    }

    @Nested
    @DisplayName("normal two-source")
    class NormalTwoSource {

        @Test
        @DisplayName("fuses vector and keyword results via RRF")
        void fusesBothSources() {
            when(vectorRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt(), anyInt()))
                    .thenReturn(List.of(vectorResult(1L, 0.9f), vectorResult(2L, 0.8f)));
            when(keywordRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt()))
                    .thenReturn(List.of(keywordResult(1L, 5.0), keywordResult(3L, 3.0)));

            List<RetrievalResult> results = hybridRetriever.retrieve(query, scope, 10, 10);

            assertThat(results).isNotEmpty();
            // Chunk 1 appears in both sources => highest RRF
            assertThat(results.get(0).getChunkId()).isEqualTo(1L);
            assertThat(results.get(0).getScoreType()).isEqualTo(ScoreType.RRF);
            assertThat(results.get(0).getRetrievalSource()).isEqualTo(RetrievalSource.HYBRID);
            assertThat(results.get(0).getMatchedSources()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("deterministic")
    class Deterministic {

        @Test
        @DisplayName("same input produces same output")
        void deterministicOutput() {
            List<RetrievalResult> vectorResults = List.of(
                    vectorResult(1L, 0.9f), vectorResult(2L, 0.8f), vectorResult(3L, 0.7f));
            List<KeywordResult> keywordResults = List.of(
                    keywordResult(1L, 5.0), keywordResult(2L, 3.0));

            when(vectorRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt(), anyInt()))
                    .thenReturn(vectorResults);
            when(keywordRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt()))
                    .thenReturn(keywordResults);

            List<RetrievalResult> first = hybridRetriever.retrieve(query, scope, 10, 10);
            List<RetrievalResult> second = hybridRetriever.retrieve(query, scope, 10, 10);

            assertThat(first).hasSameSizeAs(second);
            for (int i = 0; i < first.size(); i++) {
                assertThat(first.get(i).getChunkId()).isEqualTo(second.get(i).getChunkId());
                assertThat(first.get(i).getScore()).isEqualTo(second.get(i).getScore());
            }
        }
    }

    @Nested
    @DisplayName("source failure -> fail")
    class SourceFailure {

        @Test
        @DisplayName("vector failure causes hybrid failure")
        void vectorFailureFailsHybrid() {
            when(vectorRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt(), anyInt()))
                    .thenThrow(new RuntimeException("Vector service down"));

            assertThatThrownBy(() -> hybridRetriever.retrieve(query, scope, 10, 10))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Retrieval service temporarily unavailable");
        }

        @Test
        @DisplayName("keyword failure causes hybrid failure")
        void keywordFailureFailsHybrid() {
            when(vectorRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt(), anyInt()))
                    .thenReturn(List.of(vectorResult(1L, 0.9f)));
            when(keywordRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt()))
                    .thenThrow(new RuntimeException("ES service down"));

            assertThatThrownBy(() -> hybridRetriever.retrieve(query, scope, 10, 10))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Retrieval service temporarily unavailable");
        }
    }

    @Nested
    @DisplayName("empty source")
    class EmptySource {

        @Test
        @DisplayName("empty keyword results still produces vector-only RRF")
        void emptyKeywordSource() {
            when(vectorRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt(), anyInt()))
                    .thenReturn(List.of(vectorResult(1L, 0.9f), vectorResult(2L, 0.8f)));
            when(keywordRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt()))
                    .thenReturn(List.of());

            List<RetrievalResult> results = hybridRetriever.retrieve(query, scope, 10, 10);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).getChunkId()).isEqualTo(1L);
            assertThat(results.get(0).getScoreType()).isEqualTo(ScoreType.RRF);
        }

        @Test
        @DisplayName("empty vector results still produces keyword-only RRF")
        void emptyVectorSource() {
            when(vectorRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt(), anyInt()))
                    .thenReturn(List.of());
            when(keywordRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt()))
                    .thenReturn(List.of(keywordResult(1L, 5.0), keywordResult(2L, 3.0)));

            List<RetrievalResult> results = hybridRetriever.retrieve(query, scope, 10, 10);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).getChunkId()).isEqualTo(1L);
            assertThat(results.get(0).getScoreType()).isEqualTo(ScoreType.RRF);
        }

        @Test
        @DisplayName("both empty produces empty results")
        void bothEmpty() {
            when(vectorRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt(), anyInt()))
                    .thenReturn(List.of());
            when(keywordRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt()))
                    .thenReturn(List.of());

            List<RetrievalResult> results = hybridRetriever.retrieve(query, scope, 10, 10);

            assertThat(results).isEmpty();
        }
    }

    @Nested
    @DisplayName("duplicate candidate")
    class DuplicateCandidate {

        @Test
        @DisplayName("duplicate chunkId across sources is merged")
        void duplicateAcrossSources() {
            when(vectorRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt(), anyInt()))
                    .thenReturn(List.of(vectorResult(1L, 0.9f), vectorResult(1L, 0.8f)));
            when(keywordRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt()))
                    .thenReturn(List.of(keywordResult(1L, 5.0)));

            List<RetrievalResult> results = hybridRetriever.retrieve(query, scope, 10, 10);

            // Deduplicated to 1 result
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getChunkId()).isEqualTo(1L);
            assertThat(results.get(0).getMatchedSources()).contains(RetrievalSource.VECTOR, RetrievalSource.KEYWORD);
        }
    }

    @Nested
    @DisplayName("Retriever interface")
    class RetrieverInterface {

        @Test
        @DisplayName("source() returns HYBRID")
        void sourceReturnsHybrid() {
            assertThat(hybridRetriever.source()).isEqualTo(RetrievalSource.HYBRID);
        }

        @Test
        @DisplayName("retrieve(Query, Scope, topK) delegates to retrieve(Query, Scope, topK, topK)")
        void defaultTopK() {
            when(vectorRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt(), anyInt()))
                    .thenReturn(List.of(vectorResult(1L, 0.9f)));
            when(keywordRetriever.retrieve(anyString(), anyLong(), anyList(), any(), anyInt()))
                    .thenReturn(List.of());

            List<RetrievalResult> results = hybridRetriever.retrieve(query, scope, 5);

            assertThat(results).hasSize(1);
        }
    }
}