package com.intellidesk.retrieval.dto;

import com.intellidesk.retrieval.RetrievalMode;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RetrievalSearchResponse {

    private RetrievalMode mode;
    private boolean rerankApplied;
    private int totalResults;
    private List<RetrievalResultResponse> results;
}