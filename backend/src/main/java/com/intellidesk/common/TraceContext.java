package com.intellidesk.common;

import java.util.UUID;
import java.util.Map;
import org.slf4j.MDC;

public class TraceContext {
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    public static String peekTraceId() {
        return TRACE_ID.get();
    }

    public static String normalize(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,64}")
                ? value : UUID.randomUUID().toString().replace("-", "");
    }

    /** Thread-bound scope: restore its caller, never leak context into the next job. */
    public static Scope open(String value, Long taskId, String messageId) {
        return new Scope(normalize(value), taskId, messageId);
    }

    public static final class Scope implements AutoCloseable {
        private final String previousTrace = TRACE_ID.get();
        private final Map<String, String> previousMdc = MDC.getCopyOfContextMap();

        private Scope(String traceId, Long taskId, String messageId) {
            TRACE_ID.set(traceId);
            MDC.put("traceId", traceId);
            if (taskId != null) MDC.put("taskId", taskId.toString());
            if (messageId != null) MDC.put("messageId", normalize(messageId));
        }

        @Override
        public void close() {
            if (previousTrace == null) TRACE_ID.remove();
            else TRACE_ID.set(previousTrace);
            if (previousMdc == null) MDC.clear();
            else MDC.setContextMap(previousMdc);
        }
    }

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
