package com.intellidesk.agent.tool;

import java.util.Collections;
import java.util.Map;

public record AgentToolExecutionResult(
        boolean success,
        String content,
        String errorCode,
        String errorMessage,
        Map<String, Object> metadata
) {

    public static AgentToolExecutionResult success(String content, Map<String, Object> metadata) {
        return new AgentToolExecutionResult(true, content, null, null, metadata);
    }

    public static AgentToolExecutionResult success(String content) {
        return new AgentToolExecutionResult(true, content, null, null, Collections.emptyMap());
    }

    public static AgentToolExecutionResult failure(String errorCode, String errorMessage) {
        return new AgentToolExecutionResult(false, null, errorCode, errorMessage, Collections.emptyMap());
    }
}