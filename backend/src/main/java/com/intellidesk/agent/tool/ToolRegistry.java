package com.intellidesk.agent.tool;

import java.util.List;
import java.util.Set;

public interface ToolRegistry {

    void register(AgentTool<?> tool);

    AgentTool<?> get(String name);

    List<AgentToolDescriptor> listDescriptors();

    Set<String> registeredNames();
}