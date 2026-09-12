package com.intellidesk.agent.tool;

public record AgentToolDescriptor(
        String name,
        String description,
        String parametersSchema
) {
}