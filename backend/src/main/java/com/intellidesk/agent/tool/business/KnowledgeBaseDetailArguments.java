package com.intellidesk.agent.tool.business;

import jakarta.validation.constraints.NotNull;

/**
 * Arguments for knowledge_base_detail tool.
 * workspaceId comes from ToolExecutionContext.
 */
public record KnowledgeBaseDetailArguments(
        @NotNull
        Long knowledgeBaseId
) {
}