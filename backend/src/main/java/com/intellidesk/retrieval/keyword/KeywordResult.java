package com.intellidesk.retrieval.keyword;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class KeywordResult {

    private Long chunkId;
    private Double bm25Score;
}