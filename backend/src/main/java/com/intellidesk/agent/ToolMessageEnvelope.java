package com.intellidesk.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.intellidesk.agent.tool.AgentToolExecutionResult;
import com.intellidesk.agent.tool.AgentToolTraceCollector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured JSON envelope for TOOL message persistence.
 * <p>
 * Each TOOL message stores a sanitized JSON object in chat_message.content:
 * <pre>
 * {
 *   "toolCallId": "call_xxx",
 *   "toolName": "knowledge_search",
 *   "arguments": {...},
 *   "success": true,
 *   "result": "...",
 *   "errorCode": null,
 *   "errorMessage": null,
 *   "durationMs": 123
 * }
 * </pre>
 * <p>
 * Does NOT contain: Throwable, stack trace, raw provider response,
 * Authentication, ToolExecutionContext, system prompt, chain-of-thought.
 */
public final class ToolMessageEnvelope {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final String toolCallId;
    private final String toolName;
    private final Map<String, Object> arguments;
    private final boolean success;
    private final String result;
    private final String errorCode;
    private final String errorMessage;
    private final long durationMs;

    private ToolMessageEnvelope(String toolCallId, String toolName, Map<String, Object> arguments,
                                boolean success, String result, String errorCode, String errorMessage,
                                long durationMs) {
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.arguments = arguments != null ? Collections.unmodifiableMap(new LinkedHashMap<>(arguments)) : Collections.emptyMap();
        this.success = success;
        this.result = result;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.durationMs = durationMs;
    }

    /**
     * Build from a ToolTraceEntry.
     */
    public static ToolMessageEnvelope fromTraceEntry(AgentToolTraceCollector.ToolTraceEntry entry,
                                                      String toolCallId) {
        AgentToolExecutionResult r = entry.result();
        return new ToolMessageEnvelope(
                toolCallId,
                entry.toolName(),
                entry.arguments(),
                r != null && r.success(),
                r != null ? r.content() : null,
                r != null ? r.errorCode() : null,
                r != null ? r.errorMessage() : null,
                entry.durationMs()
        );
    }

    /**
     * Serialize to JSON string for chat_message.content.
     */
    public String toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("toolCallId", toolCallId);
        map.put("toolName", toolName);
        map.put("arguments", arguments);
        map.put("success", success);
        map.put("result", result);
        map.put("errorCode", errorCode);
        map.put("errorMessage", errorMessage);
        map.put("durationMs", durationMs);
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    public String toolCallId() { return toolCallId; }
    public String toolName() { return toolName; }
    public Map<String, Object> arguments() { return arguments; }
    public boolean success() { return success; }
    public String result() { return result; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
    public long durationMs() { return durationMs; }
}