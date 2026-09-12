package com.intellidesk.retrieval.dto;

import com.intellidesk.retrieval.RetrievalMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class RetrievalSearchRequest {

    @NotBlank
    @Size(min = 1, max = 2000)
    private String query;

    @Size(min = 1, max = 20)
    private List<Long> knowledgeBaseIds;

    @Size(max = 100)
    private List<Long> documentIds;

    private RetrievalMode mode = RetrievalMode.HYBRID;

    private Integer candidateTopK = 30;

    private Integer topK = 8;

    private Boolean rerank = false;
}