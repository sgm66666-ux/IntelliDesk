package com.intellidesk.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime-only per-request cancellation control for Agent execution.
 * <p>
 * NOT persisted to database. Provides:
 * <ul>
 *   <li>Cooperative cancellation flag ({@link #isCancelled})</li>
 *   <li>{@link Future} tracking for interrupt attempts</li>
 *   <li>Race-safe bind: if cancellation happens before Future is bound, the Future is cancelled immediately</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>{@code
 *   AgentExecutionControl control = new AgentExecutionControl();
 *   Future<?> future = executor.submit(() -> executeAgentLoop(..., control));
 *   control.bindFuture(future);
 *   // On disconnect:
 *   control.cancel();
 * }</pre>
 */
public class AgentExecutionControl {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionControl.class);

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Future<?>> futureRef = new AtomicReference<>();

    /**
     * Set the cancellation intent flag.
     * Idempotent — safe to call multiple times.
     * <p>
     * This is the first step of the cancellation protocol. After setting the flag,
     * the caller should persist the CANCELLED state in the database, then call
     * {@link #interruptFuture()} to attempt a best-effort interrupt of the background thread.
     */
    public void requestCancellation() {
        if (cancelled.compareAndSet(false, true)) {
            log.debug("Agent execution cancellation requested");
        }
    }

    /**
     * Attempt to interrupt the background thread via Future.cancel(true).
     * <p>
     * This is best-effort — provider blocking I/O (LLM, HTTP) may not respond to interrupt.
     * Cooperative cancellation checkpoints ({@link #isCancelled()}) are the authoritative
     * mechanism for stopping the agent loop.
     * <p>
     * Should be called AFTER {@link #requestCancellation()} and after the DB terminal state
     * has been persisted, so that any interrupt-induced exception in the worker thread
     * finds the cancellation flag already set.
     */
    public void interruptFuture() {
        Future<?> future = futureRef.get();
        if (future != null) {
            future.cancel(true);
            log.debug("Future.cancel(true) called on agent execution");
        }
    }

    /**
     * Cancel this agent execution (convenience method combining requestCancellation + interruptFuture).
     * Prefer the split methods for precise ordering in race-sensitive paths.
     * Idempotent — safe to call multiple times.
     */
    public void cancel() {
        requestCancellation();
        interruptFuture();
    }

    /**
     * Check if this execution has been cancelled.
     * Agent Loop should check this at cooperative cancellation points.
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Throw a runtime exception if cancelled.
     * Convenience for cooperative cancellation checkpoints.
     */
    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new AgentCancelledException("Agent execution was cancelled");
        }
    }

    /**
     * Bind the Future for this execution.
     * If cancellation already happened before bind, cancels the Future immediately.
     * This handles the race where the SSE callback fires before the Future is available.
     */
    public void bindFuture(Future<?> future) {
        futureRef.set(future);
        // If cancelled before bind, cancel the Future now
        if (cancelled.get()) {
            future.cancel(true);
        }
    }

    /**
     * Exception thrown when cooperative cancellation is detected.
     */
    public static class AgentCancelledException extends RuntimeException {
        public AgentCancelledException(String message) {
            super(message);
        }
    }
}