package com.intellidesk.benchmark;

import java.util.List;

/**
 * Per-operation provider-path facts observed at the benchmark-only proxy boundary.
 * Requested mode flags are deliberately not used to synthesize execution facts.
 */
public record RetrievalBenchmarkPathEvidence(
        boolean observed,
        int vectorCandidateCount,
        int bm25CandidateCount,
        int hybridCandidateCount,
        int candidateCountBeforeHydration,
        int hydratedResultCount,
        int rerankCandidateCount,
        boolean rerankExecuted,
        int rerankProviderCallCount,
        int postRerankHydratedResultCount,
        List<Long> vectorCandidateIds,
        List<Long> bm25CandidateIds,
        List<Long> hybridCandidateIds
) {
    public RetrievalBenchmarkPathEvidence {
        vectorCandidateIds = List.copyOf(vectorCandidateIds);
        bm25CandidateIds = List.copyOf(bm25CandidateIds);
        hybridCandidateIds = List.copyOf(hybridCandidateIds);
    }

    public static RetrievalBenchmarkPathEvidence unobserved() {
        return new RetrievalBenchmarkPathEvidence(
                false, -1, -1, -1, -1, -1, -1,
                false, 0, -1, List.of(), List.of(), List.of());
    }
}
