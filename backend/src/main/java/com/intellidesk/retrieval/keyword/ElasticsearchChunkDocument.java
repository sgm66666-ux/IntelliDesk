package com.intellidesk.retrieval.keyword;

import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
public class ElasticsearchChunkDocument {

    private long chunkId;
    private long documentId;
    private long knowledgeBaseId;
    private long workspaceId;
    private int chunkIndex;
    private String content;
    private Map<String, Object> metadata;
    private int indexGeneration;
    private long fenceToken;
    private Instant indexedAt;
}