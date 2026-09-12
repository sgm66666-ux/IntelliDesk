package com.intellidesk.retrieval.keyword;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KeywordRetriever")
class KeywordRetrieverTest {

    @Mock
    private ElasticsearchChunkIndex elasticsearchChunkIndex;

    private KeywordRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new KeywordRetriever(elasticsearchChunkIndex);
    }

    @Nested
    @DisplayName("query building")
    class QueryBuildingTests {

        @Test
        @DisplayName("should build bool query with mandatory workspace filter")
        void shouldBuildBoolQueryWithMandatoryWorkspaceFilter() {
            List<KeywordResult> results = List.of(
                    new KeywordResult(1L, 2.5),
                    new KeywordResult(2L, 1.8)
            );
            when(elasticsearchChunkIndex.search(
                    any(BoolQuery.class), anyInt(), anyLong(), anyList(), anyList()))
                    .thenReturn(results);

            retriever.retrieve("test query", 100L, List.of(1L, 2L), null, 10);

            ArgumentCaptor<BoolQuery> boolCaptor = ArgumentCaptor.forClass(BoolQuery.class);
            verify(elasticsearchChunkIndex).search(
                    boolCaptor.capture(), eq(10), eq(100L), eq(List.of(1L, 2L)), isNull());
            BoolQuery captured = boolCaptor.getValue();
            assertThat(captured.must()).isNotEmpty();
            assertThat(captured.must()).hasSize(1);
            assertThat(captured.must().get(0).isMatch()).isTrue();
            assertThat(captured.must().get(0).match().field()).isEqualTo("content");
            assertThat(captured.must().get(0).match().query().stringValue()).isEqualTo("test query");
        }

        @Test
        @DisplayName("should build bool query with knowledge base filter")
        void shouldBuildBoolQueryWithKnowledgeBaseFilter() {
            List<KeywordResult> results = List.of(new KeywordResult(1L, 2.5));
            when(elasticsearchChunkIndex.search(
                    any(BoolQuery.class), anyInt(), anyLong(), anyList(), anyList()))
                    .thenReturn(results);

            retriever.retrieve("hello", 200L, List.of(10L, 20L, 30L), null, 5);

            verify(elasticsearchChunkIndex).search(
                    any(BoolQuery.class), eq(5), eq(200L), eq(List.of(10L, 20L, 30L)), isNull());
        }

        @Test
        @DisplayName("should build bool query with optional document filter")
        void shouldBuildBoolQueryWithOptionalDocumentFilter() {
            List<Long> documentIds = List.of(100L, 200L);
            List<KeywordResult> results = List.of(new KeywordResult(1L, 2.5));
            when(elasticsearchChunkIndex.search(
                    any(BoolQuery.class), anyInt(), anyLong(), anyList(), anyList()))
                    .thenReturn(results);

            retriever.retrieve("query", 300L, List.of(1L), documentIds, 10);

            verify(elasticsearchChunkIndex).search(
                    any(BoolQuery.class), eq(10), eq(300L), eq(List.of(1L)), eq(documentIds));
        }
    }

    @Nested
    @DisplayName("validation")
    class ValidationTests {

        @Test
        @DisplayName("should reject null query")
        void shouldRejectNullQuery() {
            assertThatThrownBy(() -> retriever.retrieve(null, 1L, List.of(1L), null, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be empty");
        }

        @Test
        @DisplayName("should reject null workspace id")
        void shouldRejectNullWorkspaceId() {
            assertThatThrownBy(() -> retriever.retrieve("query", null, List.of(1L), null, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("workspaceId must not be null");
        }

        @Test
        @DisplayName("should reject empty knowledge base ids")
        void shouldRejectEmptyKnowledgeBaseIds() {
            assertThatThrownBy(() -> retriever.retrieve("query", 1L, List.of(), null, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("knowledgeBaseIds must not be empty");
        }
    }

    @Nested
    @DisplayName("result handling")
    class ResultHandlingTests {

        @Test
        @DisplayName("should return empty result for no matches")
        void shouldReturnEmptyResultForNoMatches() {
            when(elasticsearchChunkIndex.search(
                    any(BoolQuery.class), anyInt(), anyLong(), anyList(), anyList()))
                    .thenReturn(List.of());

            List<KeywordResult> results = retriever.retrieve(
                    "no match query", 1L, List.of(1L), null, 10);

            assertThat(results).isEmpty();
        }
    }
}