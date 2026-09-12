package com.intellidesk.chat.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.chat.citation.Citation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds SSE events for chat streaming.
 * All events are sanitized — no provider raw body, API key, stack trace, or internal prompt.
 */
@Component
public class SseEventBuilder {

    private static final Logger log = LoggerFactory.getLogger(SseEventBuilder.class);

    private final ObjectMapper objectMapper;

    public SseEventBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Send start event with request/conversation/message IDs.
     */
    public void sendStart(SseEmitter emitter, String requestId, Long conversationId, Long messageId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("conversationId", conversationId);
        data.put("messageId", messageId);
        send(emitter, SseEventType.start, data);
    }

    /**
     * Send a single token delta.
     */
    public void sendToken(SseEmitter emitter, String delta) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("delta", delta);
        send(emitter, SseEventType.token, data);
    }

    /**
     * Send citation list (only valid citations from server-authoritative registry).
     */
    public void sendCitations(SseEmitter emitter, List<Citation> citations) {
        send(emitter, SseEventType.citation, citations);
    }

    /**
     * Send token usage if provider provides it.
     * @param promptTokens prompt token count
     * @param completionTokens completion token count
     * @param totalTokens total token count
     */
    public void sendUsage(SseEmitter emitter, int promptTokens, int completionTokens, int totalTokens) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("promptTokens", promptTokens);
        data.put("completionTokens", completionTokens);
        data.put("totalTokens", totalTokens);
        send(emitter, SseEventType.usage, data);
    }

    /**
     * Send done event with message ID and finish reason.
     */
    public void sendDone(SseEmitter emitter, Long messageId, String finishReason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", messageId);
        data.put("finishReason", finishReason);
        send(emitter, SseEventType.done, data);
    }

    /**
     * Send sanitized error event.
     * Never includes provider raw body, API key, stack trace, or internal prompt.
     */
    public void sendError(SseEmitter emitter, String code, String sanitizedMessage) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", sanitize(code));
        data.put("message", sanitize(sanitizedMessage));
        send(emitter, SseEventType.error, data);
    }

    /**
     * Send tool_call event when Agent selects a tool.
     */
    public void sendToolCall(SseEmitter emitter, String toolName, int step) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolName", toolName);
        data.put("step", step);
        send(emitter, SseEventType.tool_call, data);
    }

    /**
     * Send tool_result event after tool execution completes.
     */
    public void sendToolResult(SseEmitter emitter, String toolName, boolean success, int resultCount, long durationMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolName", toolName);
        data.put("success", success);
        data.put("resultCount", resultCount);
        data.put("durationMs", durationMs);
        send(emitter, SseEventType.tool_result, data);
    }

    /**
     * Generate a new request ID.
     */
    public String newRequestId() {
        return UUID.randomUUID().toString();
    }

    private void send(SseEmitter emitter, SseEventType eventType, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event()
                    .name(eventType.name())
                    .data(json));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SSE event {}: {}", eventType, e.getMessage());
        } catch (IOException e) {
            log.debug("SSE send failed (client may have disconnected): {}", e.getMessage());
        }
    }

    private String sanitize(String input) {
        if (input == null) return "";
        // Truncate to prevent oversized error messages
        return input.length() > 512 ? input.substring(0, 512) : input;
    }
}