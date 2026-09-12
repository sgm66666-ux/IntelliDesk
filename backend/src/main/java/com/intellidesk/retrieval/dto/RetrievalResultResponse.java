package com.intellidesk.retrieval.dto;

import com.intellidesk.retrieval.RetrievalSource;
import com.intellidesk.retrieval.ScoreType;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class RetrievalResultResponse {

    private Long chunkId;
    private Long documentId;
    private Long knowledgeBaseId;
    private String content;
    private float score;
    private ScoreType scoreType;
    private int chunkIndex;
    private String sectionPath;
    private RetrievalSource retrievalSource;
    private List<RetrievalSource> matchedSources;
    private Map<String, Float> sourceScores;
}