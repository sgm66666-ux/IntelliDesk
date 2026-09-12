package com.intellidesk.retrieval.keyword;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("!test")
public class ElasticsearchChunkIndex {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchChunkIndex.class);

    private final ElasticsearchClient client;
    private final RetrievalProperties retrievalProperties;

    public ElasticsearchChunkIndex(ElasticsearchClient client,
                                   RetrievalProperties retrievalProperties) {
        this.client = client;
        this.retrievalProperties = retrievalProperties;
    }

    public void bulkIndex(List<ElasticsearchChunkDocument> docs, long fenceToken) {
        String indexName = retrievalProperties.getEsIndexName();

        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
            for (ElasticsearchChunkDocument doc : docs) {
                bulkBuilder.operations(op -> op.index(idx -> idx
                        .index(indexName)
                        .id(String.valueOf(doc.getChunkId()))
                        .document(doc)
                        .versionType(VersionType.ExternalGte)
                        .version(fenceToken)
                ));
            }

            BulkResponse response = client.bulk(b -> b
                    .index(indexName)
                    .operations(bulkBuilder.build().operations())
                    .refresh(Refresh.WaitFor)
            );

            if (response.errors()) {
                List<String> errorMessages = new ArrayList<>();
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        errorMessages.add(String.format("id=%s: %s", item.id(), item.error().reason()));
                    }
                }
                throw new RuntimeException("Bulk index failed: " + String.join("; ", errorMessages));
            }

            log.debug("Bulk indexed {} documents with fenceToken={}", docs.size(), fenceToken);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Bulk index failed", e);
        }
    }

    public void deleteByDocumentId(String indexName, long documentId) {
        try {
            DeleteByQueryResponse response = client.deleteByQuery(d -> d
                    .index(indexName)
                    .refresh(true)
                    .query(q -> q.term(t -> t.field("documentId").value(documentId)))
            );

            log.debug("Deleted {} chunks for documentId={} from index={}", response.deleted(), documentId, indexName);
        } catch (Exception e) {
            throw new RuntimeException("Delete by documentId failed for documentId=" + documentId, e);
        }
    }

    public List<KeywordResult> search(BoolQuery boolQuery,
                                      int size,
                                      Long workspaceId,
                                      List<Long> knowledgeBaseIds,
                                      List<Long> documentIds) {
        String indexName = retrievalProperties.getEsIndexName();

        Query completeQuery = buildCompleteQuery(boolQuery, workspaceId, knowledgeBaseIds, documentIds);

        try {
            SearchResponse<ElasticsearchChunkDocument> response = client.search(s -> s
                    .index(indexName)
                    .query(completeQuery)
                    .size(size)
                    .sort(sort -> sort.score(sc -> sc.order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                    .sort(sort -> sort.field(f -> f.field("chunkId")
                            .order(co.elastic.clients.elasticsearch._types.SortOrder.Asc))),
                    ElasticsearchChunkDocument.class
            );

            List<KeywordResult> results = new ArrayList<>();
            for (Hit<ElasticsearchChunkDocument> hit : response.hits().hits()) {
                if (hit.source() != null && hit.score() != null) {
                    results.add(new KeywordResult(hit.source().getChunkId(), (double) hit.score()));
                }
            }

            log.debug("Keyword search returned {} results", results.size());
            return results;
        } catch (Exception e) {
            throw new RuntimeException("Keyword search failed", e);
        }
    }

    private Query buildCompleteQuery(BoolQuery boolQuery,
                                     Long workspaceId,
                                     List<Long> knowledgeBaseIds,
                                     List<Long> documentIds) {
        return Query.of(q -> q.bool(b -> {
            // Copy original must/should from the BoolQuery
            if (boolQuery.must() != null && !boolQuery.must().isEmpty()) {
                b.must(boolQuery.must());
            }
            if (boolQuery.should() != null && !boolQuery.should().isEmpty()) {
                b.should(boolQuery.should());
            }

            // Add scope filters
            b.filter(f -> f.term(t -> t.field("workspaceId").value(workspaceId)));
            b.filter(f -> f.terms(t -> t.field("knowledgeBaseId")
                    .terms(ts -> ts.value(knowledgeBaseIds.stream()
                            .map(FieldValue::of)
                            .toList()))));

            if (documentIds != null && !documentIds.isEmpty()) {
                b.filter(f -> f.terms(t -> t.field("documentId")
                        .terms(ts -> ts.value(documentIds.stream()
                                .map(FieldValue::of)
                                .toList()))));
            }

            return b;
        }));
    }
}