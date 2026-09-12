package com.intellidesk.agent;

import com.intellidesk.agent.tool.AgentToolExecutionResult;

import java.util.Map;

public record AgentStep(
        int stepIndex,
        String toolName,
        Map<String, Object> arguments,
        AgentToolExecutionResult result,
        long durationMs
) {
}