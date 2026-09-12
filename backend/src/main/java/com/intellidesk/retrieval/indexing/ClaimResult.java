package com.intellidesk.retrieval.indexing;

public record ClaimResult(int generation, int attemptCount, long fenceToken, boolean claimed) {
    public static ClaimResult notClaimed() {
        return new ClaimResult(0, 0, 0, false);
    }
}