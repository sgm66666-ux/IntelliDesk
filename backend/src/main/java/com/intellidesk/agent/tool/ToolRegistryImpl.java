package com.intellidesk.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ToolRegistryImpl implements ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryImpl.class);

    private final Map<String, AgentTool<?>> tools = new LinkedHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void register(AgentTool<?> tool) {
        if (tool == null) {
            throw new IllegalArgumentException("Tool must not be null");
        }
        String name = tool.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        if (tools.containsKey(name)) {
            throw new IllegalStateException("Duplicate tool name: " + name);
        }
        if (tool.argumentType() == null) {
            throw new IllegalArgumentException("Tool argumentType must not be null for tool: " + name);
        }
        String schema = tool.parametersSchema();
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("Tool parametersSchema must not be blank for tool: " + name);
        }
        try {
            objectMapper.readTree(schema);
        } catch (Exception e) {
            throw new IllegalArgumentException("Tool parametersSchema is not valid JSON for tool: " + name, e);
        }
        tools.put(name, tool);
        log.info("Registered tool: {}", name);
    }

    @Override
    public AgentTool<?> get(String name) {
        return tools.get(name);
    }

    @Override
    public List<AgentToolDescriptor> listDescriptors() {
        return tools.values().stream()
                .map(t -> new AgentToolDescriptor(t.name(), t.description(), t.parametersSchema()))
                .toList();
    }

    @Override
    public Set<String> registeredNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }
}