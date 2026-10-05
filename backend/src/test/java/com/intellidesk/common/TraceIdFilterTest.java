package com.intellidesk.common;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class TraceIdFilterTest {
    @AfterEach void clean() { TraceContext.clear(); MDC.clear(); }
    @Test void requestHeaderResultAndMdcShareSameTrace() throws Exception {
        var request = new MockHttpServletRequest(); request.addHeader("X-Trace-Id", "trace-123");
        var response = new MockHttpServletResponse();
        new TraceIdFilter().doFilter(request, response, (req, res) -> {
            assertThat(TraceContext.getTraceId()).isEqualTo("trace-123");
            assertThat(MDC.get("traceId")).isEqualTo("trace-123");
            assertThat(Result.success().getTraceId()).isEqualTo("trace-123");
        });
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("trace-123");
        assertThat(TraceContext.peekTraceId()).isNull(); assertThat(MDC.get("traceId")).isNull();
    }
    @Test void maliciousOrOverlongHeadersAreReplaced() throws Exception {
        for (String header : new String[]{"\r\ninjected", "x".repeat(65), "   ", "路径"}) {
            var request = new MockHttpServletRequest(); request.addHeader("X-Trace-Id", header);
            var response = new MockHttpServletResponse();
            new TraceIdFilter().doFilter(request, response, (req, res) -> {});
            assertThat(response.getHeader("X-Trace-Id")).matches("[a-f0-9]{32}");
        }
    }
    @Test void failureCleansAndPreservesUnrelatedCallerMdc() {
        MDC.put("caller", "safe-marker");
        assertThatThrownBy(() -> new TraceIdFilter().doFilter(new MockHttpServletRequest(),new MockHttpServletResponse(),
                (req, res) -> { throw new ServletException("synthetic failure"); })).isInstanceOf(ServletException.class);
        assertThat(TraceContext.peekTraceId()).isNull(); assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("caller")).isEqualTo("safe-marker");
    }
    @Test void redispatchRetainsTraceAndNestedScopeRestoresParent() throws Exception {
        var request = new MockHttpServletRequest(); var first = new MockHttpServletResponse();
        var filter = new TraceIdFilter(); filter.doFilter(request, first, (req, res) -> {});
        var second = new MockHttpServletResponse(); filter.doFilter(request, second, (req, res) -> {});
        assertThat(second.getHeader("X-Trace-Id")).isEqualTo(first.getHeader("X-Trace-Id"));
        try (var parent = TraceContext.open("parent", null, null)) {
            try (var child = TraceContext.open("child", 123L, "message-1")) {
                assertThat(MDC.get("taskId")).isEqualTo("123"); assertThat(TraceContext.getTraceId()).isEqualTo("child");
            }
            assertThat(TraceContext.getTraceId()).isEqualTo("parent"); assertThat(MDC.get("taskId")).isNull();
        }
        assertThat(TraceContext.peekTraceId()).isNull();
    }
}
