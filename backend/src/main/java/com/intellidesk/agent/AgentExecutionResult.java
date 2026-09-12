package com.intellidesk.agent;

import java.util.List;

public record AgentExecutionResult(
        String finalAnswer,
        List<AgentStep> steps,
        String finishReason,
        long totalDurationMs
) {
    public static AgentExecutionResult success(String finalAnswer, List<AgentStep> steps, long totalDurationMs) {
        return new AgentExecutionResult(finalAnswer, steps, "stop", totalDurationMs);
    }

    public static AgentExecutionResult maxStepsReached(String finalAnswer, List<AgentStep> steps, long totalDurationMs) {
        return new AgentExecutionResult(finalAnswer, steps, "max_steps_reached", totalDurationMs);
    }

    public static AgentExecutionResult timeout(String partialAnswer, List<AgentStep> steps, long totalDurationMs) {
        return new AgentExecutionResult(partialAnswer != null ? partialAnswer : "", steps, "timeout", totalDurationMs);
    }

    public static AgentExecutionResult error(String errorMessage, List<AgentStep> steps, long totalDurationMs) {
        return new AgentExecutionResult(errorMessage, steps, "error", totalDurationMs);
    }
}