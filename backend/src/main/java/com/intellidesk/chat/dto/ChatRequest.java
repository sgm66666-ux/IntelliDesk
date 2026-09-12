package com.intellidesk.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {

    @NotBlank(message = "query must not be blank")
    @Size(max = 5000, message = "query must not exceed 5000 characters")
    private String query;

    private List<Long> knowledgeBaseIds;

    private List<Long> documentIds;

    private Boolean rewriteEnabled;

    /** Candidate top-K for retrieval. Overrides context default if provided. */
    private Integer candidateTopK;

    /** Final top-K after retrieval. Overrides context default if provided. Must be <= candidateTopK. */
    private Integer topK;

    /** Enable rerank. Default true. */
    private Boolean rerank;
}