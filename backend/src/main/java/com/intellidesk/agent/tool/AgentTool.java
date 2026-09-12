package com.intellidesk.agent.tool;

public interface AgentTool<A> {

    String name();

    String description();

    String parametersSchema();

    Class<A> argumentType();

    AgentToolExecutionResult execute(ToolExecutionContext ctx, A arguments);
}