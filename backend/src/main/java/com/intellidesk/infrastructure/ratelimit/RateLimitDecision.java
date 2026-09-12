package com.intellidesk.infrastructure.ratelimit;

public class RateLimitDecision {

    private final boolean allowed;
    private final long remaining;
    private final long retryAfterSeconds;
    private final long resetEpochSeconds;
    private final long limit;
    private final long windowSeconds;

    private RateLimitDecision(boolean allowed, long remaining, long retryAfterSeconds,
                              long resetEpochSeconds, long limit, long windowSeconds) {
        this.allowed = allowed;
        this.remaining = remaining;
        this.retryAfterSeconds = retryAfterSeconds;
        this.resetEpochSeconds = resetEpochSeconds;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    public static RateLimitDecision allowed() {
        return new RateLimitDecision(true, -1, 0, 0, 0, 0);
    }

    public static RateLimitDecision allowed(long remaining, long resetEpochSeconds, long limit, long windowSeconds) {
        return new RateLimitDecision(true, remaining, 0, resetEpochSeconds, limit, windowSeconds);
    }

    public static RateLimitDecision rejected(long retryAfterSeconds, long resetEpochSeconds, long limit, long windowSeconds) {
        return new RateLimitDecision(false, 0, retryAfterSeconds, resetEpochSeconds, limit, windowSeconds);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public long getRemaining() {
        return remaining;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public long getResetEpochSeconds() {
        return resetEpochSeconds;
    }

    public long getLimit() {
        return limit;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }
}