package com.intellidesk.benchmark;

import org.springframework.test.context.ActiveProfilesResolver;

/**
 * Uses mocked test infrastructure for ordinary smoke regression and the real
 * production bean graph for explicitly enabled formal benchmarks whose
 * frozen provider contract requires real retrieval/index infrastructure.
 */
public final class DocumentBenchmarkProfilesResolver implements ActiveProfilesResolver {

    @Override
    public String[] resolve(Class<?> testClass) {
        boolean realProviderExecution = Boolean.getBoolean("intellidesk.benchmark.formal")
                || Boolean.getBoolean("intellidesk.benchmark.preflight")
                || Boolean.getBoolean(RetrievalRerankDiagnosticHarness.ENABLE_PROPERTY);
        return realProviderExecution
                ? new String[]{"bench"}
                : new String[]{"test", "bench"};
    }
}
