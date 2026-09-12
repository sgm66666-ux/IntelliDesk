package com.intellidesk.agent.tool.business;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Arguments for knowledge_search tool.
 * workspaceId/authenticatedUserId come from ToolExecutionContext, never from LLM.
 */
public record KnowledgeSearchArguments(
        @NotBlank
        @Size(max = 2000)
        String query,

        @NotNull
        @Size(min = 1, max = 20)
        List<Long> knowledgeBaseIds,

        @Size(max = 100)
        List<Long> documentIds,

        @jakarta.validation.constraints.Min(1)
        @jakarta.validation.constraints.Max(20)
        Integer topK,

        Boolean rerank
) {
    public KnowledgeSearchArguments {
        if (documentIds == null) {
            documentIds = List.of();
        }
        if (topK == null) {
            topK = 5;
        }
        if (rerank == null) {
            rerank = true;
        }
    }
}