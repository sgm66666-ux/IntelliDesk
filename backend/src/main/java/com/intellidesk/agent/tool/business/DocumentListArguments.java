package com.intellidesk.agent.tool.business;

import jakarta.validation.constraints.NotNull;

/**
 * Arguments for document_list tool.
 * workspaceId comes from ToolExecutionContext.
 */
public record DocumentListArguments(
        @NotNull
        Long knowledgeBaseId,

        @jakarta.validation.constraints.Min(1)
        Integer page,

        @jakarta.validation.constraints.Min(1)
        @jakarta.validation.constraints.Max(100)
        Integer size
) {
    public DocumentListArguments {
        if (page == null) {
            page = 1;
        }
        if (size == null) {
            size = 20;
        }
    }
}