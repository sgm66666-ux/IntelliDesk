package com.intellidesk.agent.tool;

public record ToolExecutionContext(
        Long authenticatedUserId,
        Long workspaceId,
        Long conversationId,
        String traceId
) {
}