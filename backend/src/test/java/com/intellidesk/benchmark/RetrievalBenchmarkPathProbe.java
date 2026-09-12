package com.intellidesk.benchmark;

import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.keyword.KeywordResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-local, benchmark-only probe for a single synchronous RetrievalService operation.
 */
public final class RetrievalBenchmarkPathProbe {

    private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();

    private RetrievalBenchmarkPathProbe() {
    }

    public static void begin(RetrievalBenchmarkHarness.BenchmarkMode mode) {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("nested retrieval benchmark path probe");
        }
        ACTIVE.set(new State(mode));
    }

    public static RetrievalBenchmarkPathEvidence finish() {
        State state = ACTIVE.get();
        ACTIVE.remove();
        return state == null ? RetrievalBenchmarkPathEvidence.unobserved() : state.snapshot();
    }

    public static void abandon() {
        ACTIVE.remove();
    }

    static void recordVector(List<RetrievalResult> results) {
        State state = ACTIVE.get();
        if (state != null) {
            state.vectorCandidateIds = retrievalIds(results);
        }
    }

    static void recordBm25(List<KeywordResult> results) {
        State state = ACTIVE.get();
        if (state != null) {
            state.bm25CandidateIds = results.stream().map(KeywordResult::getChunkId).toList();
        }
    }

    static void recordHybrid(List<RetrievalResult> results) {
        State state = ACTIVE.get();
        if (state != null) {
            state.hybridCandidateIds = retrievalIds(results);
        }
    }

    static void recordHydration(List<RetrievalResult> results) {
        State state = ACTIVE.get();
        if (state != null) {
            state.hydrationCounts.add(results.size());
        }
    }

    static void recordRerankInput(List<RetrievalResult> candidates) {
        State state = ACTIVE.get();
        if (state != null) {
            state.rerankCandidateCount = candidates.size();
        }
    }

    static void recordProviderCall() {
        State state = ACTIVE.get();
        if (state != null) {
            state.rerankProviderCallCount++;
        }
    }

    private static List<Long> retrievalIds(List<RetrievalResult> results) {
        return results.stream().map(RetrievalResult::getChunkId).toList();
    }

    private static final class State {
        private final RetrievalBenchmarkHarness.BenchmarkMode mode;
        private List<Long> vectorCandidateIds = List.of();
        private List<Long> bm25CandidateIds = List.of();
        private List<Long> hybridCandidateIds = List.of();
        private final List<Integer> hydrationCounts = new ArrayList<>();
        private int rerankCandidateCount = -1;
        private int rerankProviderCallCount;

        private State(RetrievalBenchmarkHarness.BenchmarkMode mode) {
            this.mode = mode;
        }

        private RetrievalBenchmarkPathEvidence snapshot() {
            int beforeHydration = switch (mode) {
                case VECTOR -> vectorCandidateIds.size();
                case BM25 -> bm25CandidateIds.size();
                case HYBRID, RERANK -> hybridCandidateIds.size();
            };
            int firstHydration = hydrationCounts.isEmpty() ? -1 : hydrationCounts.get(0);
            int lastHydration = hydrationCounts.size() < 2 ? -1 : hydrationCounts.get(hydrationCounts.size() - 1);
            return new RetrievalBenchmarkPathEvidence(
                    true,
                    vectorCandidateIds.size(),
                    bm25CandidateIds.size(),
                    hybridCandidateIds.size(),
                    beforeHydration,
                    firstHydration,
                    rerankCandidateCount,
                    rerankProviderCallCount > 0,
                    rerankProviderCallCount,
                    lastHydration,
                    vectorCandidateIds,
                    bm25CandidateIds,
                    hybridCandidateIds);
        }
    }
}
