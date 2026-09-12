package com.intellidesk.retrieval;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class RetrievalResult {

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

    /** Constructor for single-source results (Wave 1/2 compatibility) */
    public RetrievalResult(Long chunkId, Long documentId, Long knowledgeBaseId,
                           String content, float score, ScoreType scoreType,
                           int chunkIndex, String sectionPath) {
        this.chunkId = chunkId;
        this.documentId = documentId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.content = content;
        this.score = score;
        this.scoreType = scoreType;
        this.chunkIndex = chunkIndex;
        this.sectionPath = sectionPath;
        this.retrievalSource = RetrievalSource.VECTOR;
        this.matchedSources = new ArrayList<>();
        this.sourceScores = new HashMap<>();
    }

    /** Constructor for RRF/Hybrid results */
    public RetrievalResult(Long chunkId, Long documentId, Long knowledgeBaseId,
                           String content, float score, ScoreType scoreType,
                           int chunkIndex, String sectionPath,
                           RetrievalSource retrievalSource,
                           List<RetrievalSource> matchedSources,
                           Map<String, Float> sourceScores) {
        this.chunkId = chunkId;
        this.documentId = documentId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.content = content;
        this.score = score;
        this.scoreType = scoreType;
        this.chunkIndex = chunkIndex;
        this.sectionPath = sectionPath;
        this.retrievalSource = retrievalSource;
        this.matchedSources = matchedSources != null ? matchedSources : new ArrayList<>();
        this.sourceScores = sourceScores != null ? sourceScores : new HashMap<>();
    }
}