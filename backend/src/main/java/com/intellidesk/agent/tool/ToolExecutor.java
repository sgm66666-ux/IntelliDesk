package com.intellidesk.agent.tool;

import java.util.Map;

public interface ToolExecutor {

    AgentToolExecutionResult execute(
            String toolName,
            Map<String, Object> rawArguments,
            ToolExecutionContext ctx
    );
}