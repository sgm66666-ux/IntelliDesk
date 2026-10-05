package com.intellidesk.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    private static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = (String) request.getAttribute(ATTRIBUTE);
        if (traceId == null) traceId = TraceContext.normalize(request.getHeader("X-Trace-Id"));
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader("X-Trace-Id", traceId);
        try (TraceContext.Scope ignored = TraceContext.open(traceId, null, null)) {
            filterChain.doFilter(request, response);
        }
    }
}
