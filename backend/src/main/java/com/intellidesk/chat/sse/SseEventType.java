package com.intellidesk.chat.sse;

/**
 * SSE event types for chat streaming.
 * Order: start → token* → citation* → usage → done
 * Error: start → token* → error
 */
public enum SseEventType {
    start,
    token,
    citation,
    usage,
    done,
    error,
    tool_call,
    tool_result
}