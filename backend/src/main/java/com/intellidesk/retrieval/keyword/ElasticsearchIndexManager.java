package com.intellidesk.retrieval.keyword;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Profile("!test")
public class ElasticsearchIndexManager {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchIndexManager.class);

    private final ElasticsearchClient client;
    private final RetrievalProperties retrievalProperties;

    public ElasticsearchIndexManager(ElasticsearchClient client,
                                     RetrievalProperties retrievalProperties) {
        this.client = client;
        this.retrievalProperties = retrievalProperties;
    }

    @PostConstruct
    public void ensureIndex() {
        String indexName = retrievalProperties.getEsIndexName();
        try {
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            if (!exists) {
                createIndex(indexName);
                log.info("Created Elasticsearch index: {}", indexName);
            } else {
                validateMapping(indexName);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize Elasticsearch index: " + indexName, e);
        }
    }

    private void createIndex(String indexName) throws Exception {
        client.indices().create(c -> c
                .index(indexName)
                .settings(s -> s
                        .numberOfShards("1")
                        .numberOfReplicas("0"))
                .mappings(m -> m
                        .dynamic(DynamicMapping.Strict)
                        .properties("chunkId", p -> p.long_(l -> l))
                        .properties("documentId", p -> p.long_(l -> l))
                        .properties("knowledgeBaseId", p -> p.long_(l -> l))
                        .properties("workspaceId", p -> p.long_(l -> l))
                        .properties("chunkIndex", p -> p.integer(i -> i))
                        .properties("content", p -> p.text(t -> t
                                .analyzer("smartcn")
                                .searchAnalyzer("smartcn")))
                        .properties("metadata", p -> p.object(o -> o.enabled(false)))
                        .properties("indexGeneration", p -> p.integer(i -> i))
                        .properties("fenceToken", p -> p.long_(l -> l))
                        .properties("indexedAt", p -> p.date(d -> d))
                )
        );
    }

    private void validateMapping(String indexName) {
        try {
            GetMappingResponse mappingResponse = client.indices().getMapping(g -> g.index(indexName));
            TypeMapping mapping = mappingResponse.get(indexName).mappings();

            Map<String, Property> properties = mapping.properties();
            if (properties == null || properties.isEmpty()) {
                log.warn("Elasticsearch index {} has no property mappings", indexName);
                return;
            }

            boolean mismatch = false;

            // Validate content field analyzer
            Property contentProp = properties.get("content");
            if (contentProp != null && contentProp.isText()) {
                String analyzer = contentProp.text().analyzer();
                if (!"smartcn".equals(analyzer)) {
                    log.error("Elasticsearch index {} mapping mismatch: content.analyzer is '{}', expected 'smartcn'",
                            indexName, analyzer);
                    mismatch = true;
                }
            } else {
                log.error("Elasticsearch index {} mapping mismatch: content field missing or not text type", indexName);
                mismatch = true;
            }

            // Validate dynamic setting
            DynamicMapping dynamic = mapping.dynamic();
            if (dynamic != DynamicMapping.Strict) {
                log.error("Elasticsearch index {} mapping mismatch: dynamic is '{}', expected 'strict'",
                        indexName, dynamic != null ? dynamic.jsonValue() : "null");
                mismatch = true;
            }

            if (mismatch) {
                log.error("Elasticsearch index {} has mapping mismatches. Consider re-creating the index.", indexName);
            } else {
                log.info("Elasticsearch index {} mapping validated successfully", indexName);
            }
        } catch (Exception e) {
            log.error("Failed to validate Elasticsearch index mapping for {}", indexName, e);
        }
    }
}