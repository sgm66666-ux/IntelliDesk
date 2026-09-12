package com.intellidesk.agent.tool.business;

import jakarta.validation.constraints.NotNull;

/**
 * Arguments for document_detail tool.
 * workspaceId comes from ToolExecutionContext.
 */
public record DocumentDetailArguments(
        @NotNull
        Long knowledgeBaseId,

        @NotNull
        Long documentId
) {
}