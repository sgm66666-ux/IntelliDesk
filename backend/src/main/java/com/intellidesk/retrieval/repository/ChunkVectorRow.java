package com.intellidesk.retrieval.repository;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ChunkVectorRow {

    private Long chunkId;
    private Long documentId;
    private Long knowledgeBaseId;
    private String content;
    private float score;
    private int chunkIndex;
    private String sectionPath;
}