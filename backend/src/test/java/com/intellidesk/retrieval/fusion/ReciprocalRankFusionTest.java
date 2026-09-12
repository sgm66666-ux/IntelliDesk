package com.intellidesk.retrieval.fusion;

import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.RetrievalSource;
import com.intellidesk.retrieval.ScoreType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {

    private final ReciprocalRankFusion rrf = new ReciprocalRankFusion(60);

    // Helper to create a result with just chunkId and score
    private RetrievalResult result(long chunkId, float score) {
        return new RetrievalResult(chunkId, null, null, "content-" + chunkId, score,
                ScoreType.COSINE_SIMILARITY, 0, null);
    }

    private RetrievalResult keywordResult(long chunkId, float score) {
        RetrievalResult r = new RetrievalResult(chunkId, null, null, "content-" + chunkId, score,
                ScoreType.BM25, 0, null);
        r.setRetrievalSource(RetrievalSource.KEYWORD);
        return r;
    }

    @Nested
    @DisplayName("exact formula")
    class ExactFormula {

        @Test
        @DisplayName("single source: RRF = 1/(k+rank)")
        void singleSourceRrfFormula() {
            List<RetrievalResult> vectorResults = List.of(
                    result(1L, 0.9f),
                    result(2L, 0.8f),
                    result(3L, 0.7f)
            );

            Map<RetrievalSource, List<RetrievalResult>> sources = new LinkedHashMap<>();
            sources.put(RetrievalSource.VECTOR, vectorResults);

            List<RetrievalResult> fused = rrf.fuse(sources, 10);

            assertThat(fused).hasSize(3);
            // Rank 1: 1/(60+1) = 1/61 ≈ 0.01639
            assertThat(fused.get(0).getChunkId()).isEqualTo(1L);
            assertThat(fused.get(0).getScore()).isGreaterThan(0.016f);
            assertThat(fused.get(0).getScore()).isLessThan(0.017f);
            // Rank 2: 1/(60+2) = 1/62 ≈ 0.01613
            assertThat(fused.get(1).getChunkId()).isEqualTo(2L);
            // Rank 3: 1/(60+3) = 1/63 ≈ 0.01587
            assertThat(fused.get(2).getChunkId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("two sources: RRF = sum of 1/(k+rank) per source")
        void twoSourceRrfFormula() {
            List<RetrievalResult> vectorResults = List.of(result(1L, 0.9f));
            List<RetrievalResult> keywordResults = List.of(keywordResult(1L, 5.0f));

            Map<RetrievalSource, List<RetrievalResult>> sources = new LinkedHashMap<>();
            sources.put(RetrievalSource.VECTOR, vectorResults);
            sources.put(RetrievalSource.KEYWORD, keywordResults);

            List<RetrievalResult> fused = rrf.fuse(sources, 10);

            assertThat(fused).hasSize(1);
            assertThat(fused.get(0).getChunkId()).isEqualTo(1L);
            // RRF = 1/61 + 1/61 = 2/61 ≈ 0.03279
            assertThat(fused.get(0).getScore()).isGreaterThan(0.032f);
            assertThat(fused.get(0).getScore()).isLessThan(0.033f);
            assertThat(fused.get(0).getScoreType()).isEqualTo(ScoreType.RRF);
            assertThat(fused.get(0).getRetrievalSource()).isEqualTo(RetrievalSource.HYBRID);
            assertThat(fused.get(0).getMatchedSources()).contains(RetrievalSource.VECTOR, RetrievalSource.KEYWORD);
        }
    }

    @Nested
    @DisplayName("vector only")
    class VectorOnly {

        @Test
        @DisplayName("no keyword results, only vector")
        void vectorOnlyFusion() {
            List<RetrievalResult> vectorResults = List.of(
                    result(1L, 0.9f), result(2L, 0.8f), result(3L, 0.7f)
            );
            List<RetrievalResult> keywordResults = List.of();

            Map<RetrievalSource, List<RetrievalResult>> sources = new LinkedHashMap<>();
            sources.put(RetrievalSource.VECTOR, vectorResults);
            sources.put(RetrievalSource.KEYWORD, keywordResults);

            List<RetrievalResult> fused = rrf.fuse(sources, 10);

            assertThat(fused).hasSize(3);
            assertThat(fused.get(0).getChunkId()).isEqualTo(1L);
            assertThat(fused.get(1).getChunkId()).isEqualTo(2L);
            assertThat(fused.get(2).getChunkId()).isEqualTo(3L);
            assertThat(fused.get(0).getScoreType()).isEqualTo(ScoreType.RRF);
        }
    }

    @Nested
    @DisplayName("keyword only")
    class KeywordOnly {

        @Test
        @DisplayName("no vector results, only keyword")
        void keywordOnlyFusion() {
            List<RetrievalResult> vectorResults = List.of();
            List<RetrievalResult> keywordResults = List.of(
                    keywordResult(1L, 5.0f), keywordResult(2L, 3.0f), keywordResult(3L, 1.0f)
            );

            Map<RetrievalSource, List<RetrievalResult>> sources = new LinkedHashMap<>();
            sources.put(RetrievalSource.VECTOR, vectorResults);
            sources.put(RetrievalSource.KEYWORD, keywordResults);

            List<RetrievalResult> fused = rrf.fuse(sources, 10);

            assertThat(fused).hasSize(3);
            assertThat(fused.get(0).getChunkId()).isEqualTo(1L);
            assertThat(fused.get(1).getChunkId()).isEqualTo(2L);
            assertThat(fused.get(2).getChunkId()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("overlap")
    class Overlap {

        @Test
        @DisplayName("overlapping chunks get combined RRF score")
        void overlappingChunks() {
            // Both sources have chunk 1, but vector has 1,2,3 and keyword has 1,4,5
            List<RetrievalResult> vectorResults = List.of(
                    result(1L, 0.9f), result(2L, 0.8f), result(3L, 0.7f)
            );
            List<RetrievalResult> keywordResults = List.of(
                    keywordResult(1L, 5.0f), keywordResult(4L, 3.0f), keywordResult(5L, 1.0f)
            );

            Map<RetrievalSource, List<RetrievalResult>> sources = new LinkedHashMap<>();
            sources.put(RetrievalSource.VECTOR, vectorResults);
            sources.put(RetrievalSource.KEYWORD, keywordResults);

            List<RetrievalResult> fused = rrf.fuse(sources, 10);

            // Chunk 1 appears in both = highest RRF score
            assertThat(fused).hasSize(5);
            assertThat(fused.get(0).getChunkId()).isEqualTo(1L);
            assertThat(fused.get(0).getMatchedSources()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("duplicate within source")
    class DuplicateWithinSource {

        @Test
        @DisplayName("duplicate chunkId in same source is deduplicated")
        void duplicateDeduplicated() {
            List<RetrievalResult> vectorResults = List.of(
                    result(1L, 0.9f), result(1L, 0.8f), result(2L, 0.7f)
            );

            Map<RetrievalSource, List<RetrievalResult>> sources = new LinkedHashMap<>();
            sources.put(RetrievalSource.VECTOR, vectorResults);

            List<RetrievalResult> fused = rrf.fuse(sources, 10);

            assertThat(fused).hasSize(2);
            assertThat(fused.get(0).getChunkId()).isEqualTo(1L);
            assertThat(fused.get(1).getChunkId()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("empty")
    class EmptyInput {

        @Test
        @DisplayName("empty sources returns empty list")
        void emptySources() {
            Map<RetrievalSource, List<RetrievalResult>> sources = new LinkedHashMap<>();
            List<RetrievalResult> fused = rrf.fuse(sources, 10);
            assertThat(fused).isEmpty();
        }

        @Test
        @DisplayName("all empty source lists returns empty")
        void allEmptySourceLists() {
            Map<RetrievalSource, List<RetrievalResult>> sources = new LinkedHashMap<>();
            sources.put(RetrievalSource.VECTOR, List.of());
            sources.put(RetrievalSource.KEYWORD, List.of());
            List<RetrievalResult> fused = rrf.fuse(sources, 10);
            assertThat(fused).isEmpty();
        }
    }

    @Nested
    @DisplayName("ties")
    class Ties {

        @Test
        @DisplayName("same RRF score -> deterministic by matched source count, then chunkId")
        void deterministicTies() {
            // Two sources, each with 2 chunks. Chunks 1 and 2 appear in both = tied RRF
            List<RetrievalResult> vectorResults = List.of(
                    result(2L, 0.9f), result(1L, 0.8f)
            );
            List<RetrievalResult> keywordResults = List.of(
                    keywordResult(1L, 5.0f), keywordResult(2L, 3.0f)
            );

            Map<RetrievalSource, List<RetrievalResult>> sources = new LinkedHashMap<>();
            sources.put(RetrievalSource.VECTOR, vectorResults);
            sources.put(RetrievalSource.KEYWORD, keywordResults);

            List<RetrievalResult> fused = rrf.fuse(sources, 10);

            assertThat(fused).hasSize(2);
            // Both have same RRF score and same matched source count (2)
            // Deterministic tie-break: chunkId asc
            assertThat(fused.get(0).getChunkId()).isEqualTo(1L);
            assertThat(fused.get(1).getChunkId()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("topK")
    class TopK {

        @Test
        @DisplayName("topK truncation")
        void topKTruncation() {
            List<RetrievalResult> vectorResults = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                vectorResults.add(result(i, 1.0f - i * 0.1f));
            }

            Map<RetrievalSource, List<RetrievalResult>> sources = new LinkedHashMap<>();
            sources.put(RetrievalSource.VECTOR, vectorResults);

            List<RetrievalResult> fused = rrf.fuse(sources, 3);

            assertThat(fused).hasSize(3);
            assertThat(fused.get(0).getChunkId()).isEqualTo(1L);
            assertThat(fused.get(1).getChunkId()).isEqualTo(2L);
            assertThat(fused.get(2).getChunkId()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("configurable k")
    class ConfigurableK {

        @Test
        @DisplayName("k=1 gives higher RRF differentiation")
        void smallK() {
            ReciprocalRankFusion smallK = new ReciprocalRankFusion(1);
            List<RetrievalResult> results = List.of(
                    result(1L, 0.9f), result(2L, 0.8f), result(3L, 0.7f)
            );

            Map<RetrievalSource, List<RetrievalResult>> sources = new LinkedHashMap<>();
            sources.put(RetrievalSource.VECTOR, results);

            List<RetrievalResult> fused = smallK.fuse(sources, 10);

            assertThat(fused).hasSize(3);
            // Rank 1: 1/(1+1)=0.5, Rank 2: 1/(1+2)=0.333, Rank 3: 1/(1+3)=0.25
            assertThat(fused.get(0).getScore()).isGreaterThan(0.49f);
            assertThat(fused.get(1).getScore()).isGreaterThan(0.33f);
            assertThat(fused.get(2).getScore()).isGreaterThan(0.24f);
        }

        @Test
        @DisplayName("k=1000 gives very close RRF scores")
        void largeK() {
            ReciprocalRankFusion largeK = new ReciprocalRankFusion(1000);
            List<RetrievalResult> results = List.of(
                    result(1L, 0.9f), result(2L, 0.8f)
            );

            Map<RetrievalSource, List<RetrievalResult>> sources = new LinkedHashMap<>();
            sources.put(RetrievalSource.VECTOR, results);

            List<RetrievalResult> fused = largeK.fuse(sources, 10);

            assertThat(fused).hasSize(2);
            // Both scores should be very close
            assertThat(Math.abs(fused.get(0).getScore() - fused.get(1).getScore())).isLessThan(0.001f);
        }

        @Test
        @DisplayName("k out of range throws")
        void invalidKThrows() {
            try {
                new ReciprocalRankFusion(0);
                assertThat(true).isFalse(); // should not reach
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage()).contains("k");
            }
            try {
                new ReciprocalRankFusion(1001);
                assertThat(true).isFalse();
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage()).contains("k");
            }
        }
    }

    @Nested
    @DisplayName("input not mutated")
    class InputImmutability {

        @Test
        @DisplayName("input lists are not modified")
        void inputNotMutated() {
            List<RetrievalResult> vectorResults = new ArrayList<>(List.of(
                    result(1L, 0.9f), result(2L, 0.8f)
            ));
            List<RetrievalResult> keywordResults = new ArrayList<>(List.of(
                    keywordResult(1L, 5.0f)
            ));

            int vectorSize = vectorResults.size();
            int keywordSize = keywordResults.size();

            Map<RetrievalSource, List<RetrievalResult>> sources = new LinkedHashMap<>();
            sources.put(RetrievalSource.VECTOR, vectorResults);
            sources.put(RetrievalSource.KEYWORD, keywordResults);

            rrf.fuse(sources, 10);

            assertThat(vectorResults).hasSize(vectorSize);
            assertThat(keywordResults).hasSize(keywordSize);
        }
    }
}