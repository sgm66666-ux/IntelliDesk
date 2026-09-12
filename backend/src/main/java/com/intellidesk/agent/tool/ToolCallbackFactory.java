package com.intellidesk.agent.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring-managed stateless factory for building ToolCallback instances.
 * <p>
 * Constructor-injected {@link ToolExecutor} is a singleton service.
 * No request-level state (userId, workspaceId, conversationId, collector, Authentication) is stored.
 * <p>
 * {@link ToolExecutionContext} and {@link AgentToolTraceCollector} are still passed only
 * via {@code ToolCallingChatOptions.toolContext()} per-request.
 */
@Component
public class ToolCallbackFactory {

    private final ToolExecutor toolExecutor;

    public ToolCallbackFactory(ToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
    }

    /**
     * Build ToolCallback list from ToolRegistry.
     * Does NOT receive request-level parameters.
     */
    public List<ToolCallback> buildCallbacks(ToolRegistry toolRegistry) {
        return toolRegistry.listDescriptors().stream()
                .map(desc -> (ToolCallback) new IntelliDeskToolCallback(desc, toolExecutor))
                .toList();
    }
}