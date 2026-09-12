package com.intellidesk.retrieval;

import com.intellidesk.embedding.EmbeddingService;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.retrieval.repository.ChunkVectorRow;
import com.intellidesk.retrieval.repository.PgVectorChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VectorRetriever")
class VectorRetrieverTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private PgVectorChunkRepository pgVectorChunkRepository;

    private RetrievalProperties properties;
    private VectorRetriever retriever;

    private static float[] createVector(int seed) {
        float[] v = new float[1536];
        for (int i = 0; i < 1536; i++) {
            v[i] = seed * 0.001f + i * 0.0001f;
        }
        return v;
    }

    @BeforeEach
    void setUp() {
        properties = new RetrievalProperties();
        properties.setTopK(10);
        properties.setCandidateTopK(20);
        properties.setHnswEfSearch(40);
        retriever = new VectorRetriever(embeddingService, pgVectorChunkRepository, properties);
    }

    @Nested
    @DisplayName("retrieve")
    class RetrieveTests {

        @Test
        @DisplayName("returns results ordered by score descending with chunkId tie-break")
        void returnsResultsOrderedByScore() {
            float[] queryVector = createVector(0);
            when(embeddingService.embedQuery("test query")).thenReturn(queryVector);

            List<ChunkVectorRow> rows = List.of(
                    new ChunkVectorRow(3L, 1L, 1L, "content3", 0.85f, 0, "section3"),
                    new ChunkVectorRow(1L, 1L, 1L, "content1", 0.95f, 0, "section1"),
                    new ChunkVectorRow(2L, 1L, 1L, "content2", 0.95f, 0, "section2"),
                    new ChunkVectorRow(4L, 2L, 1L, "content4", 0.75f, 0, "section4")
            );
            when(pgVectorChunkRepository.search(
                    eq(queryVector), eq(1L), anyList(), eq(null), eq(20)))
                    .thenReturn(rows);

            List<RetrievalResult> results = retriever.retrieve(
                    "test query", 1L, List.of(1L), null, 20, 10);

            assertThat(results).hasSize(4);
            // First: score 0.95, chunkId 1 (tie-break)
            assertThat(results.get(0).getChunkId()).isEqualTo(1L);
            assertThat(results.get(0).getScore()).isEqualTo(0.95f);
            // Second: score 0.95, chunkId 2
            assertThat(results.get(1).getChunkId()).isEqualTo(2L);
            assertThat(results.get(1).getScore()).isEqualTo(0.95f);
            // Third: score 0.85
            assertThat(results.get(2).getChunkId()).isEqualTo(3L);
            assertThat(results.get(2).getScore()).isEqualTo(0.85f);
            // Fourth: score 0.75
            assertThat(results.get(3).getChunkId()).isEqualTo(4L);
            assertThat(results.get(3).getScore()).isEqualTo(0.75f);
        }

        @Test
        @DisplayName("caps results at topK")
        void capsResultsAtTopK() {
            float[] queryVector = createVector(0);
            when(embeddingService.embedQuery("test query")).thenReturn(queryVector);

            List<ChunkVectorRow> rows = List.of(
                    new ChunkVectorRow(1L, 1L, 1L, "c1", 0.95f, 0, "s1"),
                    new ChunkVectorRow(2L, 1L, 1L, "c2", 0.85f, 0, "s2"),
                    new ChunkVectorRow(3L, 1L, 1L, "c3", 0.75f, 0, "s3")
            );
            when(pgVectorChunkRepository.search(
                    eq(queryVector), eq(1L), anyList(), eq(null), eq(20)))
                    .thenReturn(rows);

            List<RetrievalResult> results = retriever.retrieve(
                    "test query", 1L, List.of(1L), null, 20, 2);

            assertThat(results).hasSize(2);
        }

        @Test
        @DisplayName("returns empty list for no matching results")
        void returnsEmptyForNoResults() {
            float[] queryVector = createVector(0);
            when(embeddingService.embedQuery("test query")).thenReturn(queryVector);
            when(pgVectorChunkRepository.search(
                    eq(queryVector), eq(1L), anyList(), eq(null), eq(20)))
                    .thenReturn(List.of());

            List<RetrievalResult> results = retriever.retrieve(
                    "test query", 1L, List.of(1L), null, 20, 10);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("sets correct score type")
        void setsCorrectScoreType() {
            float[] queryVector = createVector(0);
            when(embeddingService.embedQuery("test query")).thenReturn(queryVector);

            List<ChunkVectorRow> rows = List.of(
                    new ChunkVectorRow(1L, 1L, 1L, "content", 0.95f, 0, "section")
            );
            when(pgVectorChunkRepository.search(
                    eq(queryVector), eq(1L), anyList(), eq(null), eq(20)))
                    .thenReturn(rows);

            List<RetrievalResult> results = retriever.retrieve(
                    "test query", 1L, List.of(1L), null, 20, 10);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getScoreType()).isEqualTo(ScoreType.COSINE_SIMILARITY);
        }
    }

    @Nested
    @DisplayName("validation")
    class ValidationTests {

        @Test
        @DisplayName("rejects null query")
        void rejectsNullQuery() {
            assertThatThrownBy(() -> retriever.retrieve(null, 1L, List.of(1L), null, 20, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be empty");
        }

        @Test
        @DisplayName("rejects blank query")
        void rejectsBlankQuery() {
            assertThatThrownBy(() -> retriever.retrieve("   ", 1L, List.of(1L), null, 20, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be empty");
        }
    }

    @Nested
    @DisplayName("score semantics")
    class ScoreSemanticsTests {

        @Test
        @DisplayName("score is cosine similarity (1 - cosine distance)")
        void scoreIsCosineSimilarity() {
            float[] queryVector = createVector(0);
            when(embeddingService.embedQuery("test query")).thenReturn(queryVector);

            ChunkVectorRow row = new ChunkVectorRow(1L, 1L, 1L, "content", 0.873f, 0, "section");
            when(pgVectorChunkRepository.search(
                    eq(queryVector), eq(1L), anyList(), eq(null), eq(20)))
                    .thenReturn(List.of(row));

            List<RetrievalResult> results = retriever.retrieve(
                    "test query", 1L, List.of(1L), null, 20, 10);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getScore()).isEqualTo(0.873f);
            assertThat(results.get(0).getScoreType()).isEqualTo(ScoreType.COSINE_SIMILARITY);
        }
    }
}