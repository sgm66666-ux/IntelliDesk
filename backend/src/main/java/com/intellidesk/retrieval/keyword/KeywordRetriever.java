package com.intellidesk.retrieval.keyword;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("!test")
public class KeywordRetriever {

    private static final Logger log = LoggerFactory.getLogger(KeywordRetriever.class);

    private final ElasticsearchChunkIndex elasticsearchChunkIndex;

    public KeywordRetriever(ElasticsearchChunkIndex elasticsearchChunkIndex) {
        this.elasticsearchChunkIndex = elasticsearchChunkIndex;
    }

    public List<KeywordResult> retrieve(String query,
                                        Long workspaceId,
                                        List<Long> knowledgeBaseIds,
                                        List<Long> documentIds,
                                        int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be empty");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("workspaceId must not be null");
        }
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            throw new IllegalArgumentException("knowledgeBaseIds must not be empty");
        }
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be positive");
        }

        BoolQuery boolQuery = BoolQuery.of(b -> b
                .must(m -> m.match(MatchQuery.of(mq -> mq
                        .field("content")
                        .query(query)))));

        List<KeywordResult> results = elasticsearchChunkIndex.search(
                boolQuery, topK, workspaceId, knowledgeBaseIds, documentIds);

        log.debug("KeywordRetriever returned {} results for query: {}",
                results.size(), query.substring(0, Math.min(50, query.length())));
        return results;
    }
}