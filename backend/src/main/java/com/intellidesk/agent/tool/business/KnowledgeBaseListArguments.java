package com.intellidesk.agent.tool.business;

/**
 * Arguments for knowledge_base_list tool.
 * workspaceId comes from ToolExecutionContext.
 */
public record KnowledgeBaseListArguments(
        @jakarta.validation.constraints.Min(1)
        Integer page,

        @jakarta.validation.constraints.Min(1)
        @jakarta.validation.constraints.Max(100)
        Integer size
) {
    public KnowledgeBaseListArguments {
        if (page == null) {
            page = 1;
        }
        if (size == null) {
            size = 20;
        }
    }
}