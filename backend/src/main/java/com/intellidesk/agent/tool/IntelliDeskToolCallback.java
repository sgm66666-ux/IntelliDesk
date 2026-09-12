package com.intellidesk.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/**
 * Spring AI ToolCallback bridge.
 * <p>
 * Stateless — all per-request context (ToolExecutionContext, AgentToolTraceCollector)
 * comes from {@link ToolContext} at call time.
 * <p>
 * buildCallbacks(toolRegistry) does NOT receive request-level parameters.
 */
public class IntelliDeskToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(IntelliDeskToolCallback.class);

    private final ToolDefinition toolDefinition;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IntelliDeskToolCallback(AgentToolDescriptor descriptor, ToolExecutor toolExecutor) {
        this.toolDefinition = DefaultToolDefinition.builder()
                .name(descriptor.name())
                .description(descriptor.description())
                .inputSchema(descriptor.parametersSchema())
                .build();
        this.toolExecutor = toolExecutor;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        log.debug("call(String) invoked with toolInput={} — should not happen without ToolContext", toolInput);
        return "Error: Tool execution requires context";
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Map<String, Object> contextMap = toolContext.getContext();
        ToolExecutionContext ctx = (ToolExecutionContext) contextMap.get("ctx");
        AgentToolTraceCollector collector = (AgentToolTraceCollector) contextMap.get("collector");

        if (ctx == null) {
            log.warn("Tool execution context (ctx) is missing from ToolContext for tool={}", getName());
            return "Error: Tool execution context is missing";
        }

        // Parse arguments once — reuse for both execution and collector
        Map<String, Object> arguments = parseArguments(toolInput);

        long start = System.currentTimeMillis();
        AgentToolExecutionResult result = toolExecutor.execute(getName(), arguments, ctx);
        long durationMs = System.currentTimeMillis() - start;

        if (collector != null) {
            collector.record(getName(), arguments, result, durationMs);
        }

        if (result.success()) {
            return result.content() != null ? result.content() : "";
        }
        return "Error: " + (result.errorMessage() != null ? result.errorMessage() : "Tool execution failed");
    }

    private String getName() {
        return toolDefinition.name();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(toolInput, Map.class);
        } catch (Exception e) {
            log.debug("Failed to parse tool input as JSON: {}", toolInput, e);
            return Map.of();
        }
    }
}