package com.intellidesk.benchmark;

import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.retrieval.RetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostics-only reproduction for the Retrieval 005 RERANK-c4 tail burst.
 *
 * <p>This harness is permanently classified as NOT_BENCHMARK_EVIDENCE. It uses
 * the SMOKE evidence purpose, a separate diagnostics namespace, and a config hash
 * that cannot be confused with Formal Retrieval evidence.
 */
@SpringBootTest
@ActiveProfiles(resolver = DocumentBenchmarkProfilesResolver.class)
@Import(TestInfrastructureConfig.class)
public class RetrievalRerankDiagnosticHarness {

    public static final String ENABLE_PROPERTY = "intellidesk.benchmark.rerankDiagnostic";
    public static final String RUN_SET_ID_PROPERTY = "retrievalRerankDiagnosticRunSetId";
    public static final String DEFAULT_RUN_SET_ID = "diag-rerank-c4-20260830-001";
    public static final int DIAGNOSTIC_CONCURRENCY = 4;
    public static final int DIAGNOSTIC_RUNS = 3;

    @Autowired
    private RetrievalService retrievalService;

    static String resolveRunSetId() {
        String value = System.getProperty(RUN_SET_ID_PROPERTY, DEFAULT_RUN_SET_ID).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "System property -D" + RUN_SET_ID_PROPERTY + " must not be empty");
        }
        return value;
    }

    static RetrievalBenchmarkHarness.BenchmarkRunSpec diagnosticSpec() {
        RetrievalBenchmarkHarness.BenchmarkRunSpec canonical =
                RetrievalBenchmarkHarness.BenchmarkRunSpec.canonical();
        return new RetrievalBenchmarkHarness.BenchmarkRunSpec(
                canonical.warmupMinSamples(),
                canonical.warmupMinOperationDurationMs(),
                canonical.minMeasuredSamples(),
                canonical.minDurationMs(),
                canonical.maxDurationMs(),
                canonical.requestTimeoutMs(),
                DIAGNOSTIC_CONCURRENCY,
                canonical.candidateTopK(),
                canonical.topK());
    }

    static Map<String, Object> buildDiagnosticPerformanceConfig() {
        Map<String, Object> config = new LinkedHashMap<>(
                RetrievalBenchmarkHarness.buildPerformanceConfig(diagnosticSpec()));
        config.put("not_benchmark_evidence", true);
        config.put("evidence_purpose", "DIAGNOSTIC_ONLY");
        config.put("diagnostic_scope", "RERANK-c4-only-runtime-profile");
        config.put("diagnostic_source_formal_run_set_id", "b-formal-retrieval-20260830-005");
        config.put("diagnostic_runs", DIAGNOSTIC_RUNS);
        return config;
    }

    @Test
    void diagnosticWhenEnabled() throws Exception {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            return;
        }

        Path projectRoot = RetrievalBenchmarkHarness.resolveProjectRoot();
        Path diagnosticsRoot = projectRoot.resolve("docs").resolve("evaluation").resolve("diagnostics");
        String runSetId = resolveRunSetId();
        RetrievalBenchmarkHarness.BenchmarkRunSpec spec = diagnosticSpec();
        Map<String, Object> performanceConfig = buildDiagnosticPerformanceConfig();
        Map<String, Object> environmentIdentity = RetrievalBenchmarkHarness.buildEnvironmentIdentity();
        String configHash = BenchmarkRunSetManager.canonicalHash(performanceConfig);
        String environmentHash = BenchmarkRunSetManager.canonicalHash(environmentIdentity);

        Path runSetDir = BenchmarkRunSetManager.createJavaRunSet(
                diagnosticsRoot,
                RetrievalBenchmarkHarness.SCENARIO,
                runSetId,
                BenchmarkRunSetManager.ArtifactContract.COMPONENT,
                BenchmarkRunSetManager.EvidencePurpose.SMOKE,
                performanceConfig,
                environmentIdentity,
                List.of("RERANK-c4"),
                DIAGNOSTIC_RUNS);
        BenchmarkRunSetManager.beginMeasurement(runSetDir);

        List<String> queries = RetrievalBenchmarkHarness.loadDatasetQuestions(
                projectRoot.resolve("docs").resolve("evaluation")
                        .resolve("dataset").resolve("evaluation_dataset.json"));
        try {
            for (int run = 1; run <= DIAGNOSTIC_RUNS; run++) {
                RetrievalBenchmarkHarness.runOneMode(
                        RetrievalBenchmarkHarness.BenchmarkMode.RERANK,
                        runSetDir,
                        "run-" + run,
                        spec,
                        retrievalService,
                        queries,
                        configHash,
                        environmentHash,
                        BenchmarkRunSetManager.EvidencePurpose.SMOKE);
            }
            BenchmarkRunSetManager.recordCleanup(
                    runSetDir,
                    true,
                    Map.of(
                            "diagnostic_only", true,
                            "not_benchmark_evidence", true,
                            "owned_resources", "none",
                            "retrieval_state_mutated", false));
        } catch (Exception error) {
            BenchmarkRunSetManager.updateStatus(
                    runSetDir, BenchmarkRunSetManager.Status.FAILED, error.getMessage());
            throw error;
        }
    }
}

