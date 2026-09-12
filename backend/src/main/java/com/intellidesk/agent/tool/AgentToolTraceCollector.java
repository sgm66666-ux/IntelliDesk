package com.intellidesk.agent.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AgentToolTraceCollector {

    private final List<ToolTraceEntry> entries = Collections.synchronizedList(new ArrayList<>());

    public void record(String toolName, Map<String, Object> arguments,
                       AgentToolExecutionResult result, long durationMs) {
        Map<String, Object> safeArgs = arguments == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(arguments));
        entries.add(new ToolTraceEntry(toolName, safeArgs, result, durationMs));
    }

    public List<ToolTraceEntry> getEntries() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public record ToolTraceEntry(
            String toolName,
            Map<String, Object> arguments,
            AgentToolExecutionResult result,
            long durationMs
    ) {
    }
}