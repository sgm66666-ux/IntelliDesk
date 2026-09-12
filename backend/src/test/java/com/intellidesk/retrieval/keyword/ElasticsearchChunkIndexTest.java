package com.intellidesk.retrieval.keyword;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ElasticsearchChunkIndex")
class ElasticsearchChunkIndexTest {

    @Mock
    private ElasticsearchClient client;

    private RetrievalProperties properties;
    private ElasticsearchChunkIndex index;

    private static final String INDEX_NAME = "intellidesk-chunks-v1";

    @BeforeEach
    void setUp() {
        properties = new RetrievalProperties();
        properties.setEsIndexName(INDEX_NAME);
        index = new ElasticsearchChunkIndex(client, properties);
    }

    private ElasticsearchChunkDocument createDoc(long chunkId, long documentId, long kbId, long workspaceId) {
        ElasticsearchChunkDocument doc = new ElasticsearchChunkDocument();
        doc.setChunkId(chunkId);
        doc.setDocumentId(documentId);
        doc.setKnowledgeBaseId(kbId);
        doc.setWorkspaceId(workspaceId);
        doc.setChunkIndex(0);
        doc.setContent("test content " + chunkId);
        doc.setMetadata(java.util.Collections.emptyMap());
        doc.setIndexGeneration(1);
        doc.setFenceToken(1000L);
        doc.setIndexedAt(Instant.now());
        return doc;
    }

    @Nested
    @DisplayName("bulkIndex")
    class BulkIndexTests {

        @Test
        @DisplayName("should bulk index with external version")
        void shouldBulkIndexWithExternalVersion() throws Exception {
            ElasticsearchChunkDocument doc1 = createDoc(1L, 100L, 10L, 1000L);
            ElasticsearchChunkDocument doc2 = createDoc(2L, 100L, 10L, 1000L);
            List<ElasticsearchChunkDocument> docs = List.of(doc1, doc2);

            BulkResponse bulkResponse = mock(BulkResponse.class);
            when(bulkResponse.errors()).thenReturn(false);
            when(client.bulk(any(Function.class))).thenReturn(bulkResponse);

            index.bulkIndex(docs, 5000L);

            verify(client).bulk(any(Function.class));
        }

        @Test
        @DisplayName("should throw on bulk partial failure")
        void shouldThrowOnBulkPartialFailure() throws Exception {
            ElasticsearchChunkDocument doc = createDoc(1L, 100L, 10L, 1000L);

            BulkResponseItem failedItem = mock(BulkResponseItem.class);
            when(failedItem.error()).thenReturn(ErrorCause.of(e -> e.reason("document missing")));
            when(failedItem.id()).thenReturn("1");

            BulkResponse bulkResponse = mock(BulkResponse.class);
            when(bulkResponse.errors()).thenReturn(true);
            when(bulkResponse.items()).thenReturn(List.of(failedItem));
            when(client.bulk(any(Function.class))).thenReturn(bulkResponse);

            assertThatThrownBy(() -> index.bulkIndex(List.of(doc), 5000L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Bulk index failed");
        }

        @Test
        @DisplayName("should throw on index operation exception")
        void shouldThrowOnIndexOperationException() throws Exception {
            ElasticsearchChunkDocument doc = createDoc(1L, 100L, 10L, 1000L);

            when(client.bulk(any(Function.class)))
                    .thenThrow(new RuntimeException("connection refused"));

            assertThatThrownBy(() -> index.bulkIndex(List.of(doc), 5000L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("connection refused");
        }
    }

    @Nested
    @DisplayName("deleteByDocumentId")
    class DeleteByDocumentIdTests {

        @Test
        @DisplayName("should delete by document id")
        void shouldDeleteByDocumentId() throws Exception {
            DeleteByQueryResponse deleteResponse = mock(DeleteByQueryResponse.class);
            when(deleteResponse.deleted()).thenReturn(5L);
            when(client.deleteByQuery(any(Function.class))).thenReturn(deleteResponse);

            index.deleteByDocumentId(INDEX_NAME, 100L);

            verify(client).deleteByQuery(any(Function.class));
        }

        @Test
        @DisplayName("should throw on delete exception")
        void shouldThrowOnDeleteException() throws Exception {
            when(client.deleteByQuery(any(Function.class)))
                    .thenThrow(new RuntimeException("index not found"));

            assertThatThrownBy(() -> index.deleteByDocumentId(INDEX_NAME, 100L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Delete by documentId failed");
        }
    }

    @Nested
    @DisplayName("search")
    class SearchTests {

        @Test
        @DisplayName("should search with all filters")
        void shouldSearchWithAllFilters() throws Exception {
            ElasticsearchChunkDocument sourceDoc = createDoc(1L, 100L, 10L, 1000L);

            @SuppressWarnings("unchecked")
            Hit<ElasticsearchChunkDocument> hit = mock(Hit.class);
            when(hit.source()).thenReturn(sourceDoc);
            when(hit.score()).thenReturn(2.5);

            @SuppressWarnings("unchecked")
            HitsMetadata<ElasticsearchChunkDocument> hitsMeta = mock(HitsMetadata.class);
            when(hitsMeta.hits()).thenReturn(List.of(hit));

            @SuppressWarnings("unchecked")
            SearchResponse<ElasticsearchChunkDocument> searchResponse = mock(SearchResponse.class);
            when(searchResponse.hits()).thenReturn(hitsMeta);
            when(client.search(any(Function.class), eq(ElasticsearchChunkDocument.class)))
                    .thenReturn(searchResponse);

            co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery boolQuery =
                    co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.of(b -> b
                            .must(m -> m.match(mq -> mq
                                    .field("content")
                                    .query("test query"))));

            List<KeywordResult> results = index.search(boolQuery, 10, 1000L, List.of(10L), List.of(100L));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getChunkId()).isEqualTo(1L);
            assertThat(results.get(0).getBm25Score()).isEqualTo(2.5);
        }

        @Test
        @DisplayName("should return empty when no hits")
        void shouldReturnEmptyWhenNoHits() throws Exception {
            @SuppressWarnings("unchecked")
            HitsMetadata<ElasticsearchChunkDocument> hitsMeta = mock(HitsMetadata.class);
            when(hitsMeta.hits()).thenReturn(List.of());

            @SuppressWarnings("unchecked")
            SearchResponse<ElasticsearchChunkDocument> searchResponse = mock(SearchResponse.class);
            when(searchResponse.hits()).thenReturn(hitsMeta);
            when(client.search(any(Function.class), eq(ElasticsearchChunkDocument.class)))
                    .thenReturn(searchResponse);

            co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery boolQuery =
                    co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.of(b -> b
                            .must(m -> m.match(mq -> mq
                                    .field("content")
                                    .query("no match"))));

            List<KeywordResult> results = index.search(boolQuery, 10, 1000L, List.of(10L), null);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("should throw on search exception")
        void shouldThrowOnSearchException() throws Exception {
            when(client.search(any(Function.class), eq(ElasticsearchChunkDocument.class)))
                    .thenThrow(new RuntimeException("search timeout"));

            co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery boolQuery =
                    co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.of(b -> b
                            .must(m -> m.match(mq -> mq
                                    .field("content")
                                    .query("query"))));

            assertThatThrownBy(() -> index.search(boolQuery, 10, 1000L, List.of(10L), null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Keyword search failed");
        }
    }
}