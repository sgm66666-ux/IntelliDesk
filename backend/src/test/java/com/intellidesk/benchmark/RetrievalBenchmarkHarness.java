package com.intellidesk.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.retrieval.RetrievalMode;
import com.intellidesk.retrieval.RetrievalQuery;
import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.RetrievalScope;
import com.intellidesk.retrieval.RetrievalService;
import com.intellidesk.TestInfrastructureConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 8 Wave 2 — COMPONENT-level retrieval benchmark harness.
 *
 * <p>Exercises the real production retrieval path through
 * {@link RetrievalService#search(RetrievalQuery, RetrievalMode, int, int, boolean)}.
 *
 * <p>Runs under the {@code bench} Spring profile so it is not auto-executed during
 * normal {@code mvn test}. Manual invocation only:
 * <pre>mvn -Dtest=RetrievalBenchmarkHarness test -Dspring.profiles.active=bench</pre>
 *
 * <p>Modes:
 * <ul>
 *   <li>VECTOR  — real pgvector + embedding</li>
 *   <li>BM25    — real Elasticsearch BM25</li>
 *   <li>HYBRID  — real vector + BM25 + RRF</li>
 *   <li>RERANK  — real hybrid + reranker</li>
 * </ul>
 *
 * <p>Warmup semantics: each mode/concurrency/run independently executes real retrieval
 * operations until both {@value #WARMUP_MIN_SAMPLES} samples and
 * {@value #WARMUP_MIN_OPERATION_DURATION_MS} ms of operation time have completed.
 * Warmups are discarded and never written to raw output or percentile input.
 *
 * <p>Raw samples are written to
 * {@code docs/evaluation/bench/retrieval/<run-set-id>/raw-retrieval-<mode>-<run_id>.jsonl}.
 */
@SpringBootTest
@ActiveProfiles(resolver = DocumentBenchmarkProfilesResolver.class)
@Import({
        TestInfrastructureConfig.class,
        RetrievalBenchmarkInstrumentationConfig.class,
        RetrievalBenchmarkFixtureManager.class
})
public class RetrievalBenchmarkHarness {

    public static final String SCENARIO = "retrieval";
    public static final String DEFAULT_RUN_SET_ID = "implementation-bench-001";
    public static final String RUN_SET_ID_PROPERTY = "retrievalBenchRunSetId";

    // Approved parameters from Phase 8 Wave 2 plan v2.2 §7 retrieval row and
    // Retrieval Duration Methodology Amendment v1.0 (2026-08-29) and
    // Retrieval Warmup Methodology Amendment v1.0 (2026-08-30).
    public static final int WARMUP_MIN_SAMPLES = 200;
    public static final long WARMUP_MIN_OPERATION_DURATION_MS = 5_000L;
    public static final int MIN_MEASURED_SAMPLES = 1000;
    public static final long MIN_DURATION_MS = 5_000L;
    public static final long MAX_DURATION_MS = 180_000L;
    public static final int RERANK_WARMUP_MIN_OPERATIONS = 30;
    public static final long RERANK_WARMUP_MIN_OPERATION_DURATION_MS = 120_000L;
    public static final int RERANK_MIN_MEASURED_OPERATIONS = 100;
    public static final long RERANK_MIN_MEASURED_OPERATION_DURATION_MS = 600_000L;
    public static final long RERANK_MAX_MEASURED_DURATION_MS = 900_000L;
    public static final long REQUEST_TIMEOUT_MS = 30_000L;
    public static final long RERANK_REQUEST_TIMEOUT_MS = 60_000L;
    public static final int PRIMARY_CONCURRENCY = 1;
    public static final List<Integer> EXTRA_CONCURRENCIES = List.of(4, 8);
    public static final int INDEPENDENT_RUNS = 3;
    public static final int CANDIDATE_TOP_K = 50;
    public static final int TOP_K = 10;
    public static final int RRF_K = 60;
    public static final String PERCENTILE_METHOD = "nearest-rank";
    public static final String PROVIDER_MODE = "real";
    public static final String PROVIDER_PATH_CONTRACT_VERSION = "1.0";
    public static final String RERANKER_QUALITY_CONFIG_HASH =
            "9cd3832be43b467c9591d9c4d94ebceb3cb851ca85210e98383aca94895c7f11";

    public enum BenchmarkMode {
        VECTOR, BM25, HYBRID, RERANK
    }

    enum ProviderPathClass {
        STANDARD_RETRIEVAL_PATH,
        REAL_RERANK_PROVIDER_PATH
    }

    enum ContractClass {
        STANDARD_RETRIEVAL_CONTRACT,
        REAL_RERANK_PROVIDER_CONTRACT
    }

    record ContractSelection(
            ContractClass contractClass,
            ProviderPathClass providerPathClass,
            boolean minimumDurationUsesActualOperationUnion
    ) {
    }

    enum RunProgress {
        CONTINUE, COMPLETE, FAIL_MAX_DURATION
    }

    enum WarmupProgress {
        CONTINUE, COMPLETE
    }

    public record BenchmarkRunSpec(
            int warmupMinSamples,
            long warmupMinOperationDurationMs,
            int minMeasuredSamples,
            long minDurationMs,
            long maxDurationMs,
            long requestTimeoutMs,
            int concurrency,
            int candidateTopK,
            int topK
    ) {
        public static BenchmarkRunSpec canonical() {
            return new BenchmarkRunSpec(
                    WARMUP_MIN_SAMPLES,
                    WARMUP_MIN_OPERATION_DURATION_MS,
                    MIN_MEASURED_SAMPLES,
                    MIN_DURATION_MS,
                    MAX_DURATION_MS,
                    REQUEST_TIMEOUT_MS,
                    PRIMARY_CONCURRENCY,
                    CANDIDATE_TOP_K,
                    TOP_K);
        }

        public static BenchmarkRunSpec smoke() {
            return new BenchmarkRunSpec(2, 0L, 3, 100L, 5_000L, 1_000L, 1, 10, 5);
        }

        public static BenchmarkRunSpec formal(BenchmarkMode mode, int concurrency) {
            ContractSelection selection = classifyContract(mode, providerPathClassFor(mode));
            if (selection.contractClass() == ContractClass.REAL_RERANK_PROVIDER_CONTRACT) {
                return new BenchmarkRunSpec(
                        RERANK_WARMUP_MIN_OPERATIONS,
                        RERANK_WARMUP_MIN_OPERATION_DURATION_MS,
                        RERANK_MIN_MEASURED_OPERATIONS,
                        RERANK_MIN_MEASURED_OPERATION_DURATION_MS,
                        RERANK_MAX_MEASURED_DURATION_MS,
                        RERANK_REQUEST_TIMEOUT_MS,
                        concurrency,
                        CANDIDATE_TOP_K,
                        TOP_K);
            }
            BenchmarkRunSpec standard = canonical();
            return new BenchmarkRunSpec(
                    standard.warmupMinSamples(), standard.warmupMinOperationDurationMs(),
                    standard.minMeasuredSamples(), standard.minDurationMs(),
                    standard.maxDurationMs(), standard.requestTimeoutMs(), concurrency,
                    standard.candidateTopK(), standard.topK());
        }
    }

    public record TimedResult(
            long startNs,
            long endNs,
            boolean success,
            String error,
            int resultCount,
            String queryId,
            RetrievalBenchmarkPathEvidence pathEvidence
    ) {
        public TimedResult(long startNs, long endNs, boolean success, String error,
                           int resultCount, String queryId) {
            this(startNs, endNs, success, error, resultCount, queryId,
                    RetrievalBenchmarkPathEvidence.unobserved());
        }

        TimedResult failPath(String failure) {
            return new TimedResult(startNs, endNs, false, failure, resultCount, queryId, pathEvidence);
        }
    }

    public record ObservedSearch(
            List<RetrievalResult> results,
            RetrievalBenchmarkPathEvidence pathEvidence
    ) {
        public ObservedSearch {
            results = List.copyOf(results);
        }
    }

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String SMOKE_CONFIG_HASH =
            "2ae529f6aa1ba50845890374762191cc8a41543aec3c1eca37f49a0e7cc847a7";
    private static final String SMOKE_ENVIRONMENT_HASH =
            "1111111111111111111111111111111111111111111111111111111111111111";

    @TempDir
    private Path tempDir;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RetrievalService retrievalService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RetrievalBenchmarkFixtureManager fixtureManager;

    public static String resolveRunSetId() {
        String prop = System.getProperty(RUN_SET_ID_PROPERTY);
        if (prop != null) {
            prop = prop.trim();
            if (prop.isEmpty()) {
                throw new IllegalArgumentException("System property -D" + RUN_SET_ID_PROPERTY + " must not be empty");
            }
            return prop;
        }
        return DEFAULT_RUN_SET_ID;
    }

    /**
     * Loads questions from the frozen Wave 1 evaluation dataset.
     */
    public static List<String> loadDatasetQuestions(Path datasetFile) throws IOException {
        List<Map<String, Object>> questions = OM.readValue(datasetFile.toFile(), new TypeReference<>() {
        });
        return questions.stream()
                .map(q -> (String) q.get("question"))
                .filter(q -> q != null && !q.isBlank())
                .toList();
    }

    static ProviderPathClass providerPathClassFor(BenchmarkMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("CONTRACT_CLASSIFICATION_FAIL: mode is required");
        }
        return switch (mode) {
            case VECTOR, BM25, HYBRID -> ProviderPathClass.STANDARD_RETRIEVAL_PATH;
            case RERANK -> ProviderPathClass.REAL_RERANK_PROVIDER_PATH;
        };
    }

    static ContractSelection classifyContract(
            BenchmarkMode mode, ProviderPathClass providerPathClass) {
        if (mode == null || providerPathClass == null) {
            throw new IllegalArgumentException(
                    "CONTRACT_CLASSIFICATION_FAIL: mode and provider path class are required");
        }
        if ((mode == BenchmarkMode.VECTOR || mode == BenchmarkMode.BM25 || mode == BenchmarkMode.HYBRID)
                && providerPathClass == ProviderPathClass.STANDARD_RETRIEVAL_PATH) {
            return new ContractSelection(
                    ContractClass.STANDARD_RETRIEVAL_CONTRACT,
                    providerPathClass,
                    false);
        }
        if (mode == BenchmarkMode.RERANK
                && providerPathClass == ProviderPathClass.REAL_RERANK_PROVIDER_PATH) {
            return new ContractSelection(
                    ContractClass.REAL_RERANK_PROVIDER_CONTRACT,
                    providerPathClass,
                    true);
        }
        throw new IllegalArgumentException(
                "CONTRACT_CLASSIFICATION_FAIL: unsupported mode/provider-path pair "
                        + mode + "/" + providerPathClass);
    }

    /**
     * Builds the canonical performance config map for the retrieval scenario.
     */
    public static Map<String, Object> buildPerformanceConfig(BenchmarkRunSpec spec) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("schema_version", "1.0");
        config.put("scenario", SCENARIO);
        config.put("target", "RetrievalService.search");
        config.put("layer", "COMPONENT");
        config.put("provider_mode", PROVIDER_MODE);
        config.put("request_timeout_authority", "measurement_contracts");
        config.put("primary_concurrency", spec.concurrency());
        config.put("extra_concurrencies", EXTRA_CONCURRENCIES);
        config.put("candidate_top_k", spec.candidateTopK());
        config.put("top_k", spec.topK());
        config.put("rrf_k", RRF_K);
        config.put("independent_runs", INDEPENDENT_RUNS);
        config.put("percentile_method", PERCENTILE_METHOD);
        config.put("repeatability_p50_cv_max", 0.10d);
        config.put("repeatability_p95_max_deviation", 0.15d);
        config.put("rerank_p99_interpretation", "descriptive_only");
        config.put("modes", List.of("VECTOR", "BM25", "HYBRID", "RERANK"));
        config.put("contract_classifier_version", "1.0");
        config.put("provider_class_methodology_version", "1.0");

        Map<String, Object> standardContract = new LinkedHashMap<>();
        standardContract.put("warmup_min_operations", WARMUP_MIN_SAMPLES);
        standardContract.put("warmup_min_operation_duration_ms", WARMUP_MIN_OPERATION_DURATION_MS);
        standardContract.put("warmup_termination", "AND");
        standardContract.put("measured_min_operations", MIN_MEASURED_SAMPLES);
        standardContract.put("measured_min_operation_duration_ms", MIN_DURATION_MS);
        standardContract.put("measured_max_wall_duration_ms", MAX_DURATION_MS);
        standardContract.put("request_timeout_ms", REQUEST_TIMEOUT_MS);
        standardContract.put("minimum_duration_authority", "measured_wall_elapsed");
        standardContract.put("insufficient_sample_failure", "BENCH_INSUFFICIENT_SAMPLE_VOLUME");

        Map<String, Object> rerankContract = new LinkedHashMap<>();
        rerankContract.put("warmup_min_operations", RERANK_WARMUP_MIN_OPERATIONS);
        rerankContract.put("warmup_min_operation_duration_ms", RERANK_WARMUP_MIN_OPERATION_DURATION_MS);
        rerankContract.put("warmup_termination", "AND");
        rerankContract.put("measured_min_operations", RERANK_MIN_MEASURED_OPERATIONS);
        rerankContract.put("measured_min_operation_duration_ms", RERANK_MIN_MEASURED_OPERATION_DURATION_MS);
        rerankContract.put("measured_max_wall_duration_ms", RERANK_MAX_MEASURED_DURATION_MS);
        rerankContract.put("request_timeout_ms", RERANK_REQUEST_TIMEOUT_MS);
        rerankContract.put("minimum_duration_authority", "actual_operation_window_union");
        rerankContract.put("insufficient_sample_failure", "BENCH_INSUFFICIENT_RERANK_SAMPLE_VOLUME");

        Map<String, Object> contracts = new LinkedHashMap<>();
        contracts.put(ContractClass.STANDARD_RETRIEVAL_CONTRACT.name(), standardContract);
        contracts.put(ContractClass.REAL_RERANK_PROVIDER_CONTRACT.name(), rerankContract);
        config.put("measurement_contracts", contracts);
        config.put("request_timeout_methodology_version", "1.0");

        Map<String, Object> modeContracts = new LinkedHashMap<>();
        for (BenchmarkMode mode : BenchmarkMode.values()) {
            ContractSelection selection = classifyContract(mode, providerPathClassFor(mode));
            modeContracts.put(mode.name(), Map.of(
                    "provider_path_class", selection.providerPathClass().name(),
                    "contract_class", selection.contractClass().name()));
        }
        config.put("mode_provider_path_contracts", modeContracts);
        config.put("provider_path_contract_version", PROVIDER_PATH_CONTRACT_VERSION);
        config.put("fixture_version", RetrievalBenchmarkFixtureManager.FIXTURE_VERSION);
        config.put("fixture_hash", RetrievalBenchmarkFixtureManager.FIXTURE_HASH);
        config.put("fixture_manifest", RetrievalBenchmarkFixtureManager.FIXTURE_MANIFEST);
        config.put("fixture_expected_documents", RetrievalFixturePreflightValidator.EXPECTED_DOCUMENTS);
        config.put("fixture_expected_chunks", RetrievalFixturePreflightValidator.EXPECTED_CHUNKS);
        config.put("expected_non_empty_for_every_query", true);
        config.put("reranker_quality_config_hash", RERANKER_QUALITY_CONFIG_HASH);
        config.put("measured_provider_path_fail_closed", true);
        return config;
    }

    /** Pure authority for the amended per-run sample/operation-duration gate. */
    static RunProgress evaluateRunProgress(
            int measuredSamples, long elapsedMs, BenchmarkRunSpec spec) {
        return evaluateRunProgress(
                measuredSamples, elapsedMs, elapsedMs, spec,
                classifyContract(BenchmarkMode.VECTOR, ProviderPathClass.STANDARD_RETRIEVAL_PATH));
    }

    static RunProgress evaluateRunProgress(
            int measuredSamples,
            long actualOperationDurationMs,
            long wallElapsedMs,
            BenchmarkRunSpec spec,
            ContractSelection selection) {
        if (selection == null) {
            throw new IllegalArgumentException("CONTRACT_CLASSIFICATION_FAIL: selection is required");
        }
        if (wallElapsedMs > spec.maxDurationMs()) {
            return RunProgress.FAIL_MAX_DURATION;
        }
        long minimumDurationMs = selection.minimumDurationUsesActualOperationUnion()
                ? actualOperationDurationMs : wallElapsedMs;
        if (measuredSamples >= spec.minMeasuredSamples()
                && minimumDurationMs >= spec.minDurationMs()) {
            return RunProgress.COMPLETE;
        }
        if (wallElapsedMs >= spec.maxDurationMs()) {
            return RunProgress.FAIL_MAX_DURATION;
        }
        return RunProgress.CONTINUE;
    }

    /** Pure authority for the approved Retrieval warmup AND gate. */
    static WarmupProgress evaluateWarmupProgress(
            int completedSamples, long operationDurationMs, BenchmarkRunSpec spec) {
        if (completedSamples >= spec.warmupMinSamples()
                && operationDurationMs >= spec.warmupMinOperationDurationMs()) {
            return WarmupProgress.COMPLETE;
        }
        return WarmupProgress.CONTINUE;
    }

    /**
     * Returns the union of actual operation intervals in a completed batch.
     * Concurrent overlaps are counted once and time outside an operation interval
     * (including scheduling gaps, sleep, and post-completion idle) is excluded.
     */
    static long actualOperationDurationNs(List<TimedResult> completedBatch) {
        List<TimedResult> ordered = completedBatch.stream()
                .sorted(Comparator.comparingLong(TimedResult::startNs))
                .toList();
        long totalNs = 0L;
        long intervalStartNs = 0L;
        long intervalEndNs = 0L;
        boolean hasInterval = false;
        for (TimedResult result : ordered) {
            long startNs = result.startNs();
            long endNs = Math.max(startNs, result.endNs());
            if (!hasInterval) {
                intervalStartNs = startNs;
                intervalEndNs = endNs;
                hasInterval = true;
            } else if (startNs <= intervalEndNs) {
                intervalEndNs = Math.max(intervalEndNs, endNs);
            } else {
                totalNs += intervalEndNs - intervalStartNs;
                intervalStartNs = startNs;
                intervalEndNs = endNs;
            }
        }
        return hasInterval ? totalNs + intervalEndNs - intervalStartNs : 0L;
    }

    /**
     * Builds the environment identity map (no secrets).
     */
    public static Map<String, Object> buildEnvironmentIdentity() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("java_version", System.getProperty("java.version"));
        env.put("java_vendor", System.getProperty("java.vendor"));
        env.put("os_name", System.getProperty("os.name"));
        env.put("os_arch", System.getProperty("os.arch"));
        env.put("jvm_name", System.getProperty("java.vm.name"));
        env.put("user_timezone", System.getProperty("user.timezone"));
        return env;
    }

    /**
     * Builds the application identity map.
     */
    public static Map<String, Object> buildApplicationIdentity() {
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("name", "IntelliDesk");
        app.put("module", "backend");
        return app;
    }

    /** Resolves the repository root when Maven runs with backend as user.dir. */
    public static Path resolveProjectRoot() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        return cwd.getFileName().toString().equals("backend") ? cwd.getParent() : cwd;
    }

    /**
     * Maps the harness benchmark mode to a production {@link RetrievalMode}.
     */
    public static RetrievalMode toRetrievalMode(BenchmarkMode mode) {
        return switch (mode) {
            case VECTOR -> RetrievalMode.VECTOR;
            case BM25 -> RetrievalMode.KEYWORD;
            case HYBRID, RERANK -> RetrievalMode.HYBRID;
        };
    }

    /**
     * Runs one mode for one run and writes measured samples to the run-set raw file.
     *
     * @return the raw file path
     */
    public static Path runOneMode(
            BenchmarkMode mode,
            Path runSetDir,
            String runId,
            BenchmarkRunSpec spec,
            RetrievalService retrievalService,
            List<String> queries,
            String configHash,
            String environmentHash,
            BenchmarkRunSetManager.EvidencePurpose evidencePurpose) throws IOException {

        return runOneMode(mode, runSetDir, runId, spec, retrievalService, queries,
                RetrievalScope.of(1L, List.of(1L)), configHash, environmentHash, evidencePurpose);
    }

    public static Path runOneMode(
            BenchmarkMode mode,
            Path runSetDir,
            String runId,
            BenchmarkRunSpec spec,
            RetrievalService retrievalService,
            List<String> queries,
            RetrievalScope scope,
            String configHash,
            String environmentHash,
            BenchmarkRunSetManager.EvidencePurpose evidencePurpose) throws IOException {

        if (queries == null || queries.isEmpty()) {
            throw new IllegalArgumentException("queries must not be empty");
        }
        if (retrievalService == null) {
            throw new IllegalArgumentException("retrievalService must not be null");
        }

        ContractSelection contractSelection = classifyContract(mode, providerPathClassFor(mode));
        String evidenceMode = mode.name() + "-c" + spec.concurrency();
        BenchmarkRawWriter writer = new BenchmarkRawWriter(
                runSetDir, SCENARIO, evidenceMode, runId, configHash, environmentHash, Instant.now().toString());

        RetrievalMode retrievalMode = toRetrievalMode(mode);
        boolean rerank = mode == BenchmarkMode.RERANK;
        ExecutorService executor = Executors.newFixedThreadPool(spec.concurrency());
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        int measuredIndex = 0;
        long measuredActualOperationDurationNs = 0L;
        long measuredActualOperationDurationMs = 0L;
        long measuredWallDurationMs = 0L;
        int queryCursor = 0;
        int warmupCompletedSamples = 0;
        long warmupOperationDurationNs = 0L;
        long warmupOperationDurationMs = 0L;
        long lastWarmupCompletionNs = System.nanoTime();

        try {
            while (evaluateWarmupProgress(
                    warmupCompletedSamples, warmupOperationDurationMs, spec) == WarmupProgress.CONTINUE) {
                int remainingSamples = Math.max(0, spec.warmupMinSamples() - warmupCompletedSamples);
                int batchSize = remainingSamples > 0
                        ? Math.min(spec.concurrency(), remainingSamples)
                        : spec.concurrency();
                List<TimedResult> batch = executeBatch(
                        retrievalService, mode, retrievalMode, scope, spec, rerank, executor,
                        queries, queryCursor, batchSize, active, maxInFlight);
                if (batch.stream().anyMatch(result -> !result.success())) {
                    throw new IllegalStateException("warmup execution failed for " + evidenceMode);
                }
                if (evidencePurpose == BenchmarkRunSetManager.EvidencePurpose.FORMAL_BENCHMARK_CANDIDATE) {
                    for (TimedResult result : batch) {
                        try {
                            RetrievalFixturePreflightValidator.validatePath(
                                    mode, result.pathEvidence(), result.resultCount());
                        } catch (IllegalStateException pathError) {
                            throw new IllegalStateException(
                                    "WARMUP_PROVIDER_PATH_NOT_EXECUTED: " + pathError.getMessage(), pathError);
                        }
                    }
                }
                lastWarmupCompletionNs = Math.max(
                        lastWarmupCompletionNs,
                        batch.stream().mapToLong(TimedResult::endNs).max().orElse(lastWarmupCompletionNs));
                warmupOperationDurationNs += actualOperationDurationNs(batch);
                warmupOperationDurationMs = warmupOperationDurationNs / 1_000_000L;
                queryCursor += batchSize;
                warmupCompletedSamples += batch.size();
            }

            long measuredStartNs = System.nanoTime();
            boolean measurementStartedAfterWarmup = measuredStartNs >= lastWarmupCompletionNs;
            if (!measurementStartedAfterWarmup) {
                throw new IllegalStateException("measured clock started before warmup completion for " + evidenceMode);
            }
            while (true) {
                RunProgress progress = evaluateRunProgress(
                        measuredIndex,
                        measuredActualOperationDurationMs,
                        measuredWallDurationMs,
                        spec,
                        contractSelection);
                if (progress == RunProgress.COMPLETE) {
                    break;
                }
                if (progress == RunProgress.FAIL_MAX_DURATION) {
                    String failureCode = contractSelection.contractClass()
                            == ContractClass.REAL_RERANK_PROVIDER_CONTRACT
                            ? "BENCH_INSUFFICIENT_RERANK_SAMPLE_VOLUME"
                            : "BENCH_INSUFFICIENT_SAMPLE_VOLUME";
                    throw new IllegalStateException(failureCode + ": samples="
                            + measuredIndex + ", measured_duration_ms=" + measuredWallDurationMs
                            + ", measured_operation_duration_ms=" + measuredActualOperationDurationMs);
                }
                List<TimedResult> batch = executeBatch(
                        retrievalService, mode, retrievalMode, scope, spec, rerank, executor,
                        queries, queryCursor, spec.concurrency(), active, maxInFlight);
                batch.sort(Comparator.comparingLong(TimedResult::startNs));
                queryCursor += spec.concurrency();
                boolean measuredBatchFailed = false;
                for (TimedResult original : batch) {
                    TimedResult result = original;
                    if (evidencePurpose == BenchmarkRunSetManager.EvidencePurpose.FORMAL_BENCHMARK_CANDIDATE
                            && result.success()) {
                        try {
                            RetrievalFixturePreflightValidator.validatePath(
                                    mode, result.pathEvidence(), result.resultCount());
                        } catch (IllegalStateException pathError) {
                            result = result.failPath(
                                    "MEASURED_PROVIDER_PATH_NOT_EXECUTED: " + pathError.getMessage());
                        }
                    }
                    measuredWallDurationMs = Math.max(
                            measuredWallDurationMs,
                            (result.endNs() - measuredStartNs) / 1_000_000L);
                    Map<String, Object> sample = buildRawSample(
                            mode, evidenceMode, evidencePurpose, runSetDir, runId, measuredIndex, result,
                            configHash, environmentHash, measuredStartNs, spec);
                    writer.writeRawLine(sample);
                    measuredIndex++;
                    measuredBatchFailed = measuredBatchFailed || !result.success();
                }
                measuredActualOperationDurationNs += actualOperationDurationNs(batch);
                measuredActualOperationDurationMs = measuredActualOperationDurationNs / 1_000_000L;
                if (measuredBatchFailed) {
                    throw new IllegalStateException(
                            "MEASURED_PROVIDER_PATH_NOT_EXECUTED: failed operation in " + evidenceMode);
                }
            }

            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("contract_class", contractSelection.contractClass().name());
            observation.put("provider_path_class", contractSelection.providerPathClass().name());
            observation.put("warmup_completed_operations", warmupCompletedSamples);
            observation.put("warmup_operation_duration_ms", warmupOperationDurationMs);
            observation.put("measurement_started_after_warmup", measurementStartedAfterWarmup);
            observation.put("max_in_flight_observed", maxInFlight.get());
            BenchmarkRunSetManager.recordRunObservation(
                    runSetDir, evidenceMode, runId, writer.getRawFile(), observation);
        } finally {
            executor.shutdownNow();
        }

        return writer.getRawFile();
    }

    private static List<TimedResult> executeBatch(
            RetrievalService retrievalService,
            BenchmarkMode mode,
            RetrievalMode retrievalMode,
            RetrievalScope scope,
            BenchmarkRunSpec spec,
            boolean rerank,
            ExecutorService executor,
            List<String> queries,
            int queryCursor,
            int batchSize,
            AtomicInteger active,
            AtomicInteger maxInFlight) {
        List<Future<TimedResult>> futures = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            String query = queries.get((queryCursor + i) % queries.size());
            String queryId = "q" + (queryCursor + i);
            futures.add(executor.submit(() -> {
                int inFlight = active.incrementAndGet();
                maxInFlight.accumulateAndGet(inFlight, Math::max);
                try {
                    return executeDirect(retrievalService, mode, query, retrievalMode, scope,
                            spec.candidateTopK(), spec.topK(), rerank, queryId);
                } finally {
                    active.decrementAndGet();
                }
            }));
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(spec.requestTimeoutMs());
        List<TimedResult> results = new ArrayList<>();
        for (Future<TimedResult> future : futures) {
            long remainingNs = deadline - System.nanoTime();
            if (remainingNs <= 0) {
                future.cancel(true);
                results.add(new TimedResult(deadline, deadline, false, "timeout", 0, "timeout"));
                continue;
            }
            try {
                results.add(future.get(remainingNs, TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                future.cancel(true);
                results.add(new TimedResult(deadline, deadline, false, "timeout", 0, "timeout"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                results.add(new TimedResult(deadline, deadline, false, "interrupted", 0, "interrupted"));
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                results.add(new TimedResult(deadline, deadline, false,
                        cause.getClass().getSimpleName() + ": " + cause.getMessage(), 0, "execution-error"));
            }
        }
        return results;
    }

    private static TimedResult executeDirect(
            RetrievalService retrievalService, BenchmarkMode mode, String query, RetrievalMode retrievalMode,
            RetrievalScope scope,
            int candidateTopK, int topK, boolean rerank, String queryId) {
        long startNs = System.nanoTime();
        try {
            ObservedSearch observed = executeObservedSearch(
                    retrievalService, mode, query, scope, candidateTopK, topK);
            return new TimedResult(
                    startNs, System.nanoTime(), true, null, observed.results().size(), queryId,
                    observed.pathEvidence());
        } catch (Exception error) {
            RetrievalBenchmarkPathProbe.abandon();
            return new TimedResult(startNs, System.nanoTime(), false,
                    error.getClass().getSimpleName() + ": " + error.getMessage(), 0, queryId);
        }
    }

    public static ObservedSearch executeObservedSearch(
            RetrievalService retrievalService,
            BenchmarkMode mode,
            String query,
            RetrievalScope scope,
            int candidateTopK,
            int topK) {
        RetrievalBenchmarkPathProbe.begin(mode);
        try {
            List<RetrievalResult> results = retrievalService.search(
                    new RetrievalQuery(query, scope), toRetrievalMode(mode),
                    candidateTopK, topK, mode == BenchmarkMode.RERANK);
            RetrievalBenchmarkPathEvidence evidence = RetrievalBenchmarkPathProbe.finish();
            return new ObservedSearch(results, evidence);
        } catch (RuntimeException | Error failure) {
            RetrievalBenchmarkPathProbe.abandon();
            throw failure;
        }
    }

    private static TimedResult executeTimed(
            RetrievalService retrievalService,
            String query,
            RetrievalMode retrievalMode,
            int candidateTopK,
            int topK,
            boolean rerank,
            long timeoutMs,
            ExecutorService executor,
            String queryId) {

        RetrievalScope scope = RetrievalScope.of(1L, List.of(1L));
        RetrievalQuery rq = new RetrievalQuery(query, scope);
        long startNs = System.nanoTime();

        Future<List<RetrievalResult>> future = executor.submit(
                () -> retrievalService.search(rq, retrievalMode, candidateTopK, topK, rerank));

        try {
            List<RetrievalResult> results = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            long endNs = System.nanoTime();
            return new TimedResult(startNs, endNs, true, null, results.size(), queryId);
        } catch (TimeoutException e) {
            future.cancel(true);
            long endNs = System.nanoTime();
            return new TimedResult(startNs, endNs, false, "timeout", 0, queryId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            long endNs = System.nanoTime();
            return new TimedResult(startNs, endNs, false, "interrupted", 0, queryId);
        } catch (ExecutionException e) {
            future.cancel(true);
            long endNs = System.nanoTime();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new TimedResult(startNs, endNs, false,
                    cause.getClass().getSimpleName() + ": " + cause.getMessage(), 0, queryId);
        }
    }

    private static Map<String, Object> buildRawSample(
            BenchmarkMode mode,
            String evidenceMode,
            BenchmarkRunSetManager.EvidencePurpose evidencePurpose,
            Path runSetDir,
            String runId,
            int sampleIndex,
            TimedResult result,
            String configHash,
            String environmentHash,
            long runStartNs,
            BenchmarkRunSpec spec) {

        double latencyMs = (result.endNs() - result.startNs()) / 1_000_000.0;
        double runRelativeMs = (result.startNs() - runStartNs) / 1_000_000.0;

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("timestamp", Instant.now().toString());
        sample.put("run_relative_time", runRelativeMs);
        sample.put("scenario", SCENARIO);
        sample.put("mode", mode.name());
        sample.put("run_set_id", runSetDir.getFileName().toString());
        sample.put("run_id", runId);
        sample.put("sample_index", sampleIndex);
        sample.put("metric_name", "retrieval_" + mode.name().toLowerCase());
        sample.put("endpoint", "RetrievalService.search(mode=" + toRetrievalMode(mode).name()
                + ",rerank=" + (mode == BenchmarkMode.RERANK) + ")");
        sample.put("latency_ms", latencyMs);
        sample.put("status_code", result.success() ? 200 : 500);
        sample.put("status", result.success() ? "success" : "failure");
        sample.put("success", result.success());
        sample.put("error", result.error());
        sample.put("concurrency", spec.concurrency());
        sample.put("provider_mode", PROVIDER_MODE);
        sample.put("config_hash", configHash);
        sample.put("environment_hash", environmentHash);
        sample.put("k6_sample_reference", result.queryId());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("retrieval_mode", toRetrievalMode(mode).name());
        meta.put("rerank", mode == BenchmarkMode.RERANK);
        meta.put("result_count", result.resultCount());
        meta.put("candidate_top_k", spec.candidateTopK());
        meta.put("top_k", spec.topK());
        meta.put("request_timeout_ms", spec.requestTimeoutMs());
        meta.put("evidence_mode", evidenceMode);
        RetrievalBenchmarkPathEvidence path = result.pathEvidence();
        meta.put("vector_candidate_count", path.vectorCandidateCount());
        meta.put("bm25_candidate_count", path.bm25CandidateCount());
        meta.put("hybrid_candidate_count", path.hybridCandidateCount());
        meta.put("candidate_count_before_hydration", path.candidateCountBeforeHydration());
        meta.put("hydrated_result_count", path.hydratedResultCount());
        meta.put("rerank_candidate_count", path.rerankCandidateCount());
        meta.put("rerank_executed", path.rerankExecuted());
        meta.put("rerank_provider_call_count", path.rerankProviderCallCount());
        meta.put("post_rerank_hydrated_result_count", path.postRerankHydratedResultCount());
        if (evidencePurpose == BenchmarkRunSetManager.EvidencePurpose.SMOKE) {
            meta.put("evidence_tag", "NOT_BENCHMARK_EVIDENCE");
        }
        sample.put("execution_metadata", meta);

        return sample;
    }

    /**
     * Runs the full retrieval benchmark for all modes and independent runs.
     *
     * <p>Intended for manual invocation under the {@code bench} profile with real
     * providers (local Ollama, Elasticsearch, reranker gateway) available.
     */
    public void runBenchmark() throws Exception {
        if (fixtureManager == null) {
            throw new IllegalStateException("PREFLIGHT_FAIL: Retrieval fixture manager is unavailable");
        }
        Path projectRoot = resolveProjectRoot();
        Path benchRoot = projectRoot.resolve("docs").resolve("evaluation").resolve("bench");

        BenchmarkRunSpec spec = BenchmarkRunSpec.canonical();
        Map<String, Object> performanceConfig = buildPerformanceConfig(spec);
        Map<String, Object> environmentIdentity = buildEnvironmentIdentity();
        String configHash = BenchmarkRunSetManager.canonicalHash(performanceConfig);
        String environmentHash = BenchmarkRunSetManager.canonicalHash(environmentIdentity);

        String runSetId = resolveRunSetId();
        List<String> expectedModes = new ArrayList<>();
        for (BenchmarkMode mode : BenchmarkMode.values()) {
            for (int concurrency : List.of(PRIMARY_CONCURRENCY, 4, 8)) {
                expectedModes.add(mode.name() + "-c" + concurrency);
            }
        }
        Path runSetDir = BenchmarkRunSetManager.createJavaRunSet(
                benchRoot, SCENARIO, runSetId,
                BenchmarkRunSetManager.ArtifactContract.COMPONENT,
                BenchmarkRunSetManager.EvidencePurpose.FORMAL_BENCHMARK_CANDIDATE,
                performanceConfig, environmentIdentity, expectedModes, INDEPENDENT_RUNS);
        RetrievalBenchmarkFixtureManager.FixtureContext fixture = null;
        boolean measurementStarted = false;
        boolean measurementCompleted = false;
        Throwable failure = null;
        try {
            fixture = fixtureManager.provision(projectRoot);
            RetrievalFixturePreflightValidator.FixtureFacts facts = fixtureManager.inspect(fixture, projectRoot);
            RetrievalFixturePreflightValidator.validateFixture(facts);
            Map<String, Object> providerIdentity =
                    fixtureManager.verifyRerankerProviderIdentity(projectRoot);
            Map<String, Object> preflight = runFixturePreflight(
                    retrievalService, fixture, facts, providerIdentity);
            BenchmarkRunSetManager.recordPreflight(runSetDir, preflight);
            BenchmarkRunSetManager.beginMeasurement(runSetDir);
            measurementStarted = true;

            List<String> queries = fixture.queries().stream()
                    .map(RetrievalBenchmarkFixtureManager.QuerySpec::question).toList();
            for (BenchmarkMode mode : BenchmarkMode.values()) {
                for (int concurrency : List.of(PRIMARY_CONCURRENCY, 4, 8)) {
                    BenchmarkRunSpec concurrencySpec = BenchmarkRunSpec.formal(mode, concurrency);
                    for (int run = 1; run <= INDEPENDENT_RUNS; run++) {
                        runOneMode(mode, runSetDir, "run-" + run, concurrencySpec,
                                retrievalService, queries, fixture.scope(), configHash, environmentHash,
                                BenchmarkRunSetManager.EvidencePurpose.FORMAL_BENCHMARK_CANDIDATE);
                    }
                }
            }
            measurementCompleted = true;
        } catch (Exception error) {
            failure = error;
        } finally {
            boolean cleanupSucceeded = false;
            try {
                fixtureManager.cleanup();
                cleanupSucceeded = true;
            } catch (Exception cleanupError) {
                if (failure == null) {
                    failure = cleanupError;
                } else {
                    failure.addSuppressed(cleanupError);
                }
            }

            if (measurementStarted
                    && BenchmarkRunSetManager.readStatus(runSetDir) == BenchmarkRunSetManager.Status.PARTIAL) {
                BenchmarkRunSetManager.recordCleanup(runSetDir, cleanupSucceeded,
                        Map.of(
                                "fixture_version", RetrievalBenchmarkFixtureManager.FIXTURE_VERSION,
                                "fixture_hash", RetrievalBenchmarkFixtureManager.FIXTURE_HASH,
                                "owned_resources", "dedicated PostgreSQL logical fixture plus dedicated Elasticsearch index",
                                "retrieval_state_mutated", true,
                                "retrieval_state_restored", cleanupSucceeded,
                                "measurement_completed", measurementCompleted));
            }
            if (failure != null
                    && BenchmarkRunSetManager.readStatus(runSetDir) != BenchmarkRunSetManager.Status.FAILED) {
                if (!measurementStarted) {
                    try {
                        BenchmarkRunSetManager.recordPreflight(runSetDir, Map.of(
                                "success", false,
                                "failure_code", "PREFLIGHT_FAIL",
                                "failure_type", failure.getClass().getSimpleName()));
                    } catch (Exception ignored) {
                        // A successful preflight may already have been recorded immediately before beginMeasurement.
                    }
                }
                BenchmarkRunSetManager.updateStatus(
                        runSetDir, BenchmarkRunSetManager.Status.FAILED, failure.getMessage());
            }
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure != null) {
            throw new RuntimeException(failure);
        }
    }

    static Map<String, Object> runFixturePreflight(
            RetrievalService retrievalService,
            RetrievalBenchmarkFixtureManager.FixtureContext fixture,
            RetrievalFixturePreflightValidator.FixtureFacts facts,
            Map<String, Object> providerIdentity) {
        Map<String, Map<String, Integer>> modeAggregates = new LinkedHashMap<>();
        int providerCalls = 0;
        for (BenchmarkMode mode : BenchmarkMode.values()) {
            int minimumCandidates = Integer.MAX_VALUE;
            int minimumHydrated = Integer.MAX_VALUE;
            int minimumFinal = Integer.MAX_VALUE;
            for (RetrievalBenchmarkFixtureManager.QuerySpec query : fixture.queries()) {
                ObservedSearch observed = executeObservedSearch(
                        retrievalService, mode, query.question(), fixture.scope(), CANDIDATE_TOP_K, TOP_K);
                RetrievalFixturePreflightValidator.validatePath(
                        mode, observed.pathEvidence(), observed.results().size());

                if (query.qualifiedQualityQuestion()) {
                    Set<Long> expectedIds = query.expectedLogicalChunkIds().stream()
                            .map(fixture.chunkIds()::get)
                            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
                    if (expectedIds.contains(null) || expectedIds.isEmpty()) {
                        throw new IllegalStateException(
                                "PREFLIGHT_FAIL: missing runtime expected candidate for query " + query.id());
                    }
                    List<Long> candidateIds = switch (mode) {
                        case VECTOR -> observed.pathEvidence().vectorCandidateIds();
                        case BM25 -> observed.pathEvidence().bm25CandidateIds();
                        case HYBRID, RERANK -> observed.pathEvidence().hybridCandidateIds();
                    };
                    if (candidateIds.stream().noneMatch(expectedIds::contains)) {
                        throw new IllegalStateException(
                                "PREFLIGHT_FAIL: query-to-expected-candidate relationship failed for "
                                        + mode + "/" + query.id());
                    }
                }
                minimumCandidates = Math.min(
                        minimumCandidates, observed.pathEvidence().candidateCountBeforeHydration());
                minimumHydrated = Math.min(
                        minimumHydrated, observed.pathEvidence().hydratedResultCount());
                minimumFinal = Math.min(minimumFinal, observed.results().size());
                providerCalls += observed.pathEvidence().rerankProviderCallCount();
            }
            modeAggregates.put(mode.name(), Map.of(
                    "queries_checked", fixture.queries().size(),
                    "minimum_candidate_count_before_hydration", minimumCandidates,
                    "minimum_hydrated_result_count", minimumHydrated,
                    "minimum_final_result_count", minimumFinal));
        }
        if (providerCalls != fixture.queries().size()) {
            throw new IllegalStateException(
                    "PREFLIGHT_FAIL: RERANK provider call count does not equal query count");
        }

        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("success", true);
        observation.put("fixture_version", RetrievalBenchmarkFixtureManager.FIXTURE_VERSION);
        observation.put("fixture_hash", fixture.fixtureHash());
        observation.put("workspace_runtime_id", fixture.workspaceId());
        observation.put("knowledge_base_runtime_id", fixture.knowledgeBaseId());
        observation.put("document_count", fixture.documentIds().size());
        observation.put("chunk_count", fixture.chunkIds().size());
        observation.put("query_relationships_checked", fixture.queries().size());
        observation.put("fixture_facts", OM.convertValue(facts, new TypeReference<Map<String, Object>>() { }));
        observation.put("mode_path_aggregates", modeAggregates);
        observation.put("rerank_provider_call_count", providerCalls);
        observation.put("rerank_provider_identity", providerIdentity);
        observation.put("reranker_quality_config_hash", RERANKER_QUALITY_CONFIG_HASH);
        return observation;
    }

    @Test
    void formalBenchmarkWhenEnabled() throws Exception {
        if (!Boolean.getBoolean("intellidesk.benchmark.formal")) {
            return;
        }
        runBenchmark();
    }

    /** Real fixture/provider-path dry run. It never enters measurement or writes percentile evidence. */
    @Test
    void fixturePreflightOnlyWhenEnabled() throws Exception {
        if (!Boolean.getBoolean("intellidesk.benchmark.preflight")) {
            return;
        }
        if (Boolean.getBoolean("intellidesk.benchmark.formal")) {
            throw new IllegalStateException(
                    "PREFLIGHT_FAIL: standalone preflight and Formal cannot be enabled together");
        }
        if (retrievalService == null || fixtureManager == null) {
            throw new IllegalStateException("PREFLIGHT_FAIL: real benchmark beans are unavailable");
        }
        Path projectRoot = resolveProjectRoot();
        RetrievalBenchmarkFixtureManager.FixtureContext fixture = null;
        try {
            fixture = fixtureManager.provision(projectRoot);
            RetrievalFixturePreflightValidator.FixtureFacts facts =
                    fixtureManager.inspect(fixture, projectRoot);
            RetrievalFixturePreflightValidator.validateFixture(facts);
            Map<String, Object> providerIdentity =
                    fixtureManager.verifyRerankerProviderIdentity(projectRoot);
            Map<String, Object> observation = runFixturePreflight(
                    retrievalService, fixture, facts, providerIdentity);
            System.out.println("RETRIEVAL_PREFLIGHT_PASS " + OM.writeValueAsString(observation));
        } finally {
            if (fixture != null) {
                fixtureManager.cleanup();
            }
        }
    }

    /**
     * Minimal smoke test — NOT_BENCHMARK_EVIDENCE.
     *
     * <p>Verifies the harness can create a run-set, execute one mode with a mocked
     * retrieval service, and write raw samples with the correct schema. Uses the
     * {@code test} profile so it runs during normal targeted benchmark test passes.
     */
    @Test
    void smokeRunProducesCompleteRunSet() throws Exception {
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);
        String runSetId = "retrieval-smoke-" + UUID.randomUUID();
        BenchmarkRunSpec spec = BenchmarkRunSpec.smoke();
        Map<String, Object> performanceConfig = buildPerformanceConfig(spec);
        Map<String, Object> environmentIdentity = buildEnvironmentIdentity();
        String configHash = BenchmarkRunSetManager.canonicalHash(performanceConfig);
        String environmentHash = BenchmarkRunSetManager.canonicalHash(environmentIdentity);
        Path runSetDir = BenchmarkRunSetManager.createJavaRunSet(
                benchRoot, SCENARIO, runSetId,
                BenchmarkRunSetManager.ArtifactContract.COMPONENT,
                BenchmarkRunSetManager.EvidencePurpose.SMOKE,
                performanceConfig, environmentIdentity, List.of("VECTOR-c1"), 1);
        BenchmarkRunSetManager.beginMeasurement(runSetDir);

        RetrievalService retrievalService = Mockito.mock(RetrievalService.class);
        Mockito.when(retrievalService.search(
                        Mockito.any(RetrievalQuery.class),
                        Mockito.any(RetrievalMode.class),
                        Mockito.anyInt(),
                        Mockito.anyInt(),
                        Mockito.anyBoolean()))
                .thenReturn(List.of(new RetrievalResult(
                        1L, 1L, 1L, "chunk", 0.9f,
                        com.intellidesk.retrieval.ScoreType.COSINE_SIMILARITY, 0, null)));

        Path rawFile = runOneMode(
                BenchmarkMode.VECTOR, runSetDir, "run-1", spec,
                retrievalService, List.of("benchmark query"),
                configHash, environmentHash, BenchmarkRunSetManager.EvidencePurpose.SMOKE);

        assert Files.exists(rawFile) : "raw file must exist";
        List<String> lines = Files.readAllLines(rawFile).stream()
                .filter(l -> !l.isBlank()).toList();
        assert lines.size() >= spec.minMeasuredSamples()
                : "expected at least " + spec.minMeasuredSamples() + " measured samples";

        BenchmarkRunSetManager.recordCleanup(
                runSetDir, true, Map.of("owned_resources", "temporary-only"));
        assert BenchmarkRunSetManager.readStatus(runSetDir) == BenchmarkRunSetManager.Status.PARTIAL;
    }
}
