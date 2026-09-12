package com.intellidesk.common;

import java.util.UUID;

public class TraceContext {
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String getTraceId() {
        String traceId = TRACE_ID.get();
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
            TRACE_ID.set(traceId);
        }
        return traceId;
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}