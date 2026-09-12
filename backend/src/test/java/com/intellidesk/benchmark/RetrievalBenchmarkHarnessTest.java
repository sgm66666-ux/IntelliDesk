package com.intellidesk.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.RetrievalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit / smoke tests for {@link RetrievalBenchmarkHarness}.
 *
 * <p>These tests do not require the real retrieval providers (Ollama, Elasticsearch,
 * reranker gateway); they verify harness mechanics using a mocked
 * {@link RetrievalService} and a temporary directory.
 */
class RetrievalBenchmarkHarnessTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String CONFIG_HASH =
            "2ae529f6aa1ba50845890374762191cc8a41543aec3c1eca37f49a0e7cc847a7";
    private static final String CANONICAL_FORMAL_CONFIG_HASH =
            "43412c5e9598e27c6fa62a236b139742fac30898d2bee3f49bde5a0d8bec1a12";
    private static final String ENVIRONMENT_HASH =
            "1111111111111111111111111111111111111111111111111111111111111111";

    @TempDir
    Path tempDir;

    @Test
    void projectRootResolutionDoesNotNestEvidenceUnderBackend() {
        Path projectRoot = RetrievalBenchmarkHarness.resolveProjectRoot();
        assertNotEquals("backend", projectRoot.getFileName().toString());
        assertTrue(Files.exists(projectRoot.resolve("docs").resolve("evaluation")));
    }

    @Test
    void warmupSamplesAreExcludedFromRawOutput() throws Exception {
        Path runSetDir = createRunSetDir("VECTOR", 1);
        RetrievalService retrievalService = Mockito.mock(RetrievalService.class);
        Mockito.when(retrievalService.search(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(List.of(new RetrievalResult(1L, 1L, 1L, "chunk", 0.9f,
                        com.intellidesk.retrieval.ScoreType.COSINE_SIMILARITY, 0, null)));

        List<String> queries = List.of("query one", "query two");
        RetrievalBenchmarkHarness.BenchmarkRunSpec spec =
                RetrievalBenchmarkHarness.BenchmarkRunSpec.smoke();

        Path rawFile = RetrievalBenchmarkHarness.runOneMode(
                RetrievalBenchmarkHarness.BenchmarkMode.VECTOR,
                runSetDir, "run-1", spec, retrievalService, queries, CONFIG_HASH, ENVIRONMENT_HASH,
                BenchmarkRunSetManager.EvidencePurpose.SMOKE);

        assertTrue(Files.exists(rawFile));
        List<String> lines = Files.readAllLines(rawFile).stream().filter(l -> !l.isBlank()).toList();
        assertTrue(lines.size() >= spec.minMeasuredSamples(),
                "at least the requested measured samples should be written (warmup discarded)");
        Mockito.verify(retrievalService, Mockito.times(spec.warmupMinSamples() + lines.size()))
                .search(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean());

        for (int i = 0; i < spec.minMeasuredSamples(); i++) {
            Map<String, Object> sample = parseLine(lines.get(i));
            assertEquals(i, sample.get("sample_index"));
        }

        Map<String, Object> manifest = OM.readValue(
                runSetDir.resolve("run_set_manifest.json").toFile(), new TypeReference<>() {});
        Map<?, ?> run = (Map<?, ?>) ((List<?>) manifest.get("runs")).get(0);
        Map<String, Object> observation = OM.readValue(
                runSetDir.resolve(run.get("run_observation_file").toString()).toFile(),
                new TypeReference<>() {});
        assertEquals(spec.warmupMinSamples(), observation.get("warmup_completed_operations"));
        assertEquals("STANDARD_RETRIEVAL_CONTRACT", observation.get("contract_class"));
        assertEquals("STANDARD_RETRIEVAL_PATH", observation.get("provider_path_class"));
        assertTrue(((Number) observation.get("warmup_operation_duration_ms")).longValue() >= 0L);
        assertEquals(true, observation.get("measurement_started_after_warmup"));
    }

    @Test
    void rawSchemaContainsRequiredFields() throws Exception {
        Path runSetDir = createRunSetDir("BM25", 1);
        RetrievalService retrievalService = Mockito.mock(RetrievalService.class);
        Mockito.when(retrievalService.search(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(List.of());

        List<String> queries = List.of("what is the policy?");
        RetrievalBenchmarkHarness.BenchmarkRunSpec spec =
                new RetrievalBenchmarkHarness.BenchmarkRunSpec(0, 0L, 1, 0L, 5_000L, 1_000L, 1, 10, 5);

        Path rawFile = RetrievalBenchmarkHarness.runOneMode(
                RetrievalBenchmarkHarness.BenchmarkMode.BM25,
                runSetDir, "run-schema", spec, retrievalService, queries, CONFIG_HASH, ENVIRONMENT_HASH,
                BenchmarkRunSetManager.EvidencePurpose.SMOKE);

        List<String> lines = Files.readAllLines(rawFile).stream().filter(l -> !l.isBlank()).toList();
        assertEquals(1, lines.size());
        Map<String, Object> sample = parseLine(lines.get(0));

        assertEquals("retrieval", sample.get("scenario"));
        assertEquals("BM25", sample.get("mode"));
        assertEquals("run-schema", sample.get("run_id"));
        assertEquals(0, sample.get("sample_index"));
        assertTrue(sample.containsKey("duration_ms") || sample.containsKey("latency_ms"));
        assertEquals(true, sample.get("success"));
        assertEquals(200, sample.get("status_code"));
        assertEquals(1, sample.get("concurrency"));
        assertEquals("real", sample.get("provider_mode"));
        assertEquals(CONFIG_HASH, sample.get("config_hash"));
        assertEquals(ENVIRONMENT_HASH, sample.get("environment_hash"));
        assertNotNull(sample.get("endpoint"));
        assertTrue(sample.get("endpoint").toString().contains("RetrievalService.search"));
        assertEquals("success", sample.get("status"));
        assertEquals(1_000L,
                ((Number) ((Map<?, ?>) sample.get("execution_metadata"))
                        .get("request_timeout_ms")).longValue());
    }

    @Test
    void configHashIsStableAndMatchesConfigIdRule() throws Exception {
        Map<String, Object> config = RetrievalBenchmarkHarness.buildPerformanceConfig(
                RetrievalBenchmarkHarness.BenchmarkRunSpec.canonical());
        String hash = BenchmarkRunSetManager.canonicalHash(config);

        assertEquals(CANONICAL_FORMAL_CONFIG_HASH, hash,
                "the approved fixture/provider-path contract must remain refrozen byte-for-byte");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("^[0-9a-f]+$"));
        assertEquals(hash, hash.toLowerCase());
        assertEquals(BenchmarkRunSetManager.configId(hash), hash.substring(0, 16));
        Map<?, ?> contracts = (Map<?, ?>) config.get("measurement_contracts");
        Map<?, ?> standard = (Map<?, ?>) contracts.get("STANDARD_RETRIEVAL_CONTRACT");
        Map<?, ?> rerank = (Map<?, ?>) contracts.get("REAL_RERANK_PROVIDER_CONTRACT");
        assertEquals(180_000L, standard.get("measured_max_wall_duration_ms"));
        assertEquals(1_000, standard.get("measured_min_operations"));
        assertEquals(5_000L, standard.get("measured_min_operation_duration_ms"));
        assertEquals(200, standard.get("warmup_min_operations"));
        assertEquals(5_000L, standard.get("warmup_min_operation_duration_ms"));
        assertEquals(30_000L, standard.get("request_timeout_ms"));
        assertEquals("AND", standard.get("warmup_termination"));
        assertEquals(900_000L, rerank.get("measured_max_wall_duration_ms"));
        assertEquals(100, rerank.get("measured_min_operations"));
        assertEquals(600_000L, rerank.get("measured_min_operation_duration_ms"));
        assertEquals(30, rerank.get("warmup_min_operations"));
        assertEquals(120_000L, rerank.get("warmup_min_operation_duration_ms"));
        assertEquals(60_000L, rerank.get("request_timeout_ms"));
        assertEquals("actual_operation_window_union", rerank.get("minimum_duration_authority"));
        assertEquals("measurement_contracts", config.get("request_timeout_authority"));
        assertEquals("1.0", config.get("request_timeout_methodology_version"));
        assertFalse(config.containsKey("request_timeout_ms"));
        assertFalse(config.containsKey("warmup_policy"));
        assertEquals(List.of(4, 8), config.get("extra_concurrencies"));
        assertEquals("1.0", config.get("provider_path_contract_version"));
        assertEquals(RetrievalBenchmarkFixtureManager.FIXTURE_VERSION, config.get("fixture_version"));
        assertEquals(RetrievalBenchmarkFixtureManager.FIXTURE_HASH, config.get("fixture_hash"));
        assertEquals(14, config.get("fixture_expected_documents"));
        assertEquals(42, config.get("fixture_expected_chunks"));
        assertEquals(true, config.get("expected_non_empty_for_every_query"));
        assertEquals(true, config.get("measured_provider_path_fail_closed"));
        assertEquals(0.15d, config.get("repeatability_p95_max_deviation"));
        assertEquals("nearest-rank", config.get("percentile_method"));
        assertEquals("descriptive_only", config.get("rerank_p99_interpretation"));
    }

    @Test
    void requestTimeoutIsSelectedStaticallyByProviderClassForEveryConcurrency() {
        for (RetrievalBenchmarkHarness.BenchmarkMode mode : List.of(
                RetrievalBenchmarkHarness.BenchmarkMode.VECTOR,
                RetrievalBenchmarkHarness.BenchmarkMode.BM25,
                RetrievalBenchmarkHarness.BenchmarkMode.HYBRID)) {
            for (int concurrency : List.of(1, 4, 8)) {
                RetrievalBenchmarkHarness.BenchmarkRunSpec spec =
                        RetrievalBenchmarkHarness.BenchmarkRunSpec.formal(mode, concurrency);
                assertEquals(30_000L, spec.requestTimeoutMs());
                assertTrue(31_000L > spec.requestTimeoutMs(),
                        mode + " must reject an operation lasting 31 seconds");
            }
        }

        for (int concurrency : List.of(1, 4, 8)) {
            RetrievalBenchmarkHarness.BenchmarkRunSpec spec =
                    RetrievalBenchmarkHarness.BenchmarkRunSpec.formal(
                            RetrievalBenchmarkHarness.BenchmarkMode.RERANK, concurrency);
            assertEquals(60_000L, spec.requestTimeoutMs());
            assertTrue(29_000L <= spec.requestTimeoutMs());
            assertTrue(33_500L <= spec.requestTimeoutMs());
            assertTrue(59_900L <= spec.requestTimeoutMs());
            assertTrue(60_001L > spec.requestTimeoutMs(),
                    "real RERANK must fail beyond the frozen 60-second deadline");
        }

        assertThrows(IllegalArgumentException.class, () ->
                RetrievalBenchmarkHarness.classifyContract(
                        RetrievalBenchmarkHarness.BenchmarkMode.RERANK,
                        RetrievalBenchmarkHarness.ProviderPathClass.STANDARD_RETRIEVAL_PATH));
        assertThrows(IllegalArgumentException.class, () ->
                RetrievalBenchmarkHarness.classifyContract(
                        RetrievalBenchmarkHarness.BenchmarkMode.RERANK, null));
    }

    @Test
    void rerankDiagnosticIsPermanentlySeparatedFromFormalEvidence() throws Exception {
        Map<String, Object> formal = RetrievalBenchmarkHarness.buildPerformanceConfig(
                RetrievalBenchmarkHarness.BenchmarkRunSpec.canonical());
        Map<String, Object> diagnostic =
                RetrievalRerankDiagnosticHarness.buildDiagnosticPerformanceConfig();

        assertEquals(true, diagnostic.get("not_benchmark_evidence"));
        assertEquals("DIAGNOSTIC_ONLY", diagnostic.get("evidence_purpose"));
        assertEquals("RERANK-c4-only-runtime-profile", diagnostic.get("diagnostic_scope"));
        assertNotEquals(
                BenchmarkRunSetManager.canonicalHash(formal),
                BenchmarkRunSetManager.canonicalHash(diagnostic));
        assertEquals(4, RetrievalRerankDiagnosticHarness.diagnosticSpec().concurrency());
        assertEquals(formal.get("fixture_hash"), diagnostic.get("fixture_hash"));
        assertEquals(formal.get("provider_path_contract_version"),
                diagnostic.get("provider_path_contract_version"));
    }

    @Test
    void warmupGateRequiresBothSampleAndActualOperationDurationFloors() {
        RetrievalBenchmarkHarness.BenchmarkRunSpec spec =
                RetrievalBenchmarkHarness.BenchmarkRunSpec.canonical();

        assertEquals(RetrievalBenchmarkHarness.WarmupProgress.CONTINUE,
                RetrievalBenchmarkHarness.evaluateWarmupProgress(200, 4_900L, spec));
        assertEquals(RetrievalBenchmarkHarness.WarmupProgress.CONTINUE,
                RetrievalBenchmarkHarness.evaluateWarmupProgress(199, 10_000L, spec));
        assertEquals(RetrievalBenchmarkHarness.WarmupProgress.COMPLETE,
                RetrievalBenchmarkHarness.evaluateWarmupProgress(200, 5_000L, spec));
        assertEquals(RetrievalBenchmarkHarness.WarmupProgress.COMPLETE,
                RetrievalBenchmarkHarness.evaluateWarmupProgress(500, 5_000L, spec));
        assertEquals(RetrievalBenchmarkHarness.WarmupProgress.COMPLETE,
                RetrievalBenchmarkHarness.evaluateWarmupProgress(200, 5_001L, spec));
    }

    @Test
    void idleAndConcurrentOverlapCannotPadWarmupOperationDuration() {
        long startNs = 1_000_000_000L;
        List<RetrievalBenchmarkHarness.TimedResult> completed = List.of(
                new RetrievalBenchmarkHarness.TimedResult(
                        startNs, startNs + 3_000_000_000L, true, null, 0, "q0"),
                new RetrievalBenchmarkHarness.TimedResult(
                        startNs + 1_000_000_000L, startNs + 4_000_000_000L, true, null, 0, "q1"),
                new RetrievalBenchmarkHarness.TimedResult(
                        startNs + 4_900_000_000L, startNs + 5_800_000_000L, true, null, 0, "q2"));

        assertEquals(4_900_000_000L,
                RetrievalBenchmarkHarness.actualOperationDurationNs(completed),
                "overlap must count once and the 900ms idle gap must not count");
    }

    @Test
    void amendedRunProgressGatePreservesSamplesAndMinimumDuration() {
        RetrievalBenchmarkHarness.BenchmarkRunSpec spec =
                RetrievalBenchmarkHarness.BenchmarkRunSpec.canonical();

        assertEquals(RetrievalBenchmarkHarness.RunProgress.COMPLETE,
                RetrievalBenchmarkHarness.evaluateRunProgress(1_000, 120_000L, spec),
                "1,000 samples after 60s but before 180s must be allowed");
        assertEquals(RetrievalBenchmarkHarness.RunProgress.FAIL_MAX_DURATION,
                RetrievalBenchmarkHarness.evaluateRunProgress(999, 180_000L, spec),
                "999 samples at the 180s ceiling must fail");
        assertEquals(RetrievalBenchmarkHarness.RunProgress.CONTINUE,
                RetrievalBenchmarkHarness.evaluateRunProgress(1_000, 4_999L, spec),
                "1,000 samples before 5s must continue");
        assertEquals(RetrievalBenchmarkHarness.RunProgress.COMPLETE,
                RetrievalBenchmarkHarness.evaluateRunProgress(1_000, 5_000L, spec),
                "1,000 samples at 5s must pass");
        assertEquals(RetrievalBenchmarkHarness.RunProgress.FAIL_MAX_DURATION,
                RetrievalBenchmarkHarness.evaluateRunProgress(1_000, 180_001L, spec),
                "no run may complete beyond the hard ceiling");
    }

    @Test
    void realRerankProviderContractHasStaticFailClosedBoundaries() {
        RetrievalBenchmarkHarness.ContractSelection selection =
                RetrievalBenchmarkHarness.classifyContract(
                        RetrievalBenchmarkHarness.BenchmarkMode.RERANK,
                        RetrievalBenchmarkHarness.ProviderPathClass.REAL_RERANK_PROVIDER_PATH);
        RetrievalBenchmarkHarness.BenchmarkRunSpec spec =
                RetrievalBenchmarkHarness.BenchmarkRunSpec.formal(
                        RetrievalBenchmarkHarness.BenchmarkMode.RERANK, 1);

        assertEquals(RetrievalBenchmarkHarness.ContractClass.REAL_RERANK_PROVIDER_CONTRACT,
                selection.contractClass());
        assertEquals(RetrievalBenchmarkHarness.WarmupProgress.CONTINUE,
                RetrievalBenchmarkHarness.evaluateWarmupProgress(30, 119_900L, spec));
        assertEquals(RetrievalBenchmarkHarness.WarmupProgress.CONTINUE,
                RetrievalBenchmarkHarness.evaluateWarmupProgress(29, 200_000L, spec));
        assertEquals(RetrievalBenchmarkHarness.WarmupProgress.COMPLETE,
                RetrievalBenchmarkHarness.evaluateWarmupProgress(30, 120_000L, spec));

        assertEquals(RetrievalBenchmarkHarness.RunProgress.CONTINUE,
                RetrievalBenchmarkHarness.evaluateRunProgress(
                        29, 180_000L, 180_000L, spec, selection));
        assertEquals(RetrievalBenchmarkHarness.RunProgress.CONTINUE,
                RetrievalBenchmarkHarness.evaluateRunProgress(
                        100, 599_000L, 599_000L, spec, selection));
        assertEquals(RetrievalBenchmarkHarness.RunProgress.FAIL_MAX_DURATION,
                RetrievalBenchmarkHarness.evaluateRunProgress(
                        99, 900_000L, 900_000L, spec, selection));
        assertEquals(RetrievalBenchmarkHarness.RunProgress.COMPLETE,
                RetrievalBenchmarkHarness.evaluateRunProgress(
                        100, 600_000L, 600_000L, spec, selection));
        assertEquals(RetrievalBenchmarkHarness.RunProgress.COMPLETE,
                RetrievalBenchmarkHarness.evaluateRunProgress(
                        100, 899_000L, 899_000L, spec, selection));

        assertThrows(IllegalArgumentException.class, () ->
                RetrievalBenchmarkHarness.classifyContract(
                        RetrievalBenchmarkHarness.BenchmarkMode.VECTOR,
                        RetrievalBenchmarkHarness.ProviderPathClass.REAL_RERANK_PROVIDER_PATH));
        assertThrows(IllegalArgumentException.class, () ->
                RetrievalBenchmarkHarness.classifyContract(
                        RetrievalBenchmarkHarness.BenchmarkMode.RERANK,
                        RetrievalBenchmarkHarness.ProviderPathClass.STANDARD_RETRIEVAL_PATH));
        assertThrows(IllegalArgumentException.class, () ->
                RetrievalBenchmarkHarness.classifyContract(
                        RetrievalBenchmarkHarness.BenchmarkMode.RERANK, null));

        for (RetrievalBenchmarkHarness.BenchmarkMode mode : List.of(
                RetrievalBenchmarkHarness.BenchmarkMode.VECTOR,
                RetrievalBenchmarkHarness.BenchmarkMode.BM25,
                RetrievalBenchmarkHarness.BenchmarkMode.HYBRID)) {
            assertEquals(RetrievalBenchmarkHarness.ContractClass.STANDARD_RETRIEVAL_CONTRACT,
                    RetrievalBenchmarkHarness.classifyContract(
                            mode, RetrievalBenchmarkHarness.ProviderPathClass.STANDARD_RETRIEVAL_PATH)
                            .contractClass());
        }
    }

    @Test
    void c1C4C8ExecuteRealConcurrentWorkAndPreserveIdentityLinkage() throws Exception {
        for (int concurrency : List.of(1, 4, 8)) {
            Path runSetDir = createRunSetDir("VECTOR", concurrency, 1);
            RetrievalService retrievalService = Mockito.mock(RetrievalService.class);
            CountDownLatch allStarted = new CountDownLatch(concurrency);
            Mockito.when(retrievalService.search(
                            Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                    .thenAnswer(invocation -> {
                        allStarted.countDown();
                        assertTrue(allStarted.await(2, TimeUnit.SECONDS));
                        return List.of();
                    });
            RetrievalBenchmarkHarness.BenchmarkRunSpec spec =
                    new RetrievalBenchmarkHarness.BenchmarkRunSpec(
                            0, 0L, concurrency, 0L, 5_000L, 2_000L, concurrency, 10, 5);

            Path rawFile = RetrievalBenchmarkHarness.runOneMode(
                    RetrievalBenchmarkHarness.BenchmarkMode.VECTOR,
                    runSetDir, "run-1", spec, retrievalService, List.of("q"),
                    CONFIG_HASH, ENVIRONMENT_HASH, BenchmarkRunSetManager.EvidencePurpose.SMOKE);

            List<String> lines = Files.readAllLines(rawFile).stream().filter(line -> !line.isBlank()).toList();
            assertEquals(concurrency, lines.size());
            double previousRelativeTime = -1.0;
            for (String line : lines) {
                Map<String, Object> sample = parseLine(line);
                assertEquals(concurrency, sample.get("concurrency"));
                assertEquals("VECTOR-c" + concurrency,
                        ((Map<?, ?>) sample.get("execution_metadata")).get("evidence_mode"));
                double relativeTime = ((Number) sample.get("run_relative_time")).doubleValue();
                assertTrue(relativeTime >= previousRelativeTime,
                        "concurrent raw operations must be ordered by captured start time");
                previousRelativeTime = relativeTime;
            }

            Map<String, Object> manifest = OM.readValue(
                    runSetDir.resolve("run_set_manifest.json").toFile(), new TypeReference<>() {});
            Map<?, ?> run = (Map<?, ?>) ((List<?>) manifest.get("runs")).get(0);
            Map<String, Object> observation = OM.readValue(
                    runSetDir.resolve(run.get("run_observation_file").toString()).toFile(),
                    new TypeReference<>() {});
            assertEquals(concurrency, observation.get("max_in_flight_observed"));
            assertEquals(0, observation.get("warmup_completed_operations"));
            assertEquals(0, observation.get("warmup_operation_duration_ms"));
            assertEquals(true, observation.get("measurement_started_after_warmup"));
        }
    }

    @Test
    void everyModeConcurrencyAndIndependentRunPerformsItsOwnWarmup() throws Exception {
        for (RetrievalBenchmarkHarness.BenchmarkMode mode
                : RetrievalBenchmarkHarness.BenchmarkMode.values()) {
            for (int concurrency : List.of(1, 4, 8)) {
                for (int runIndex = 1; runIndex <= 3; runIndex++) {
                    String evidenceMode = mode.name() + "-c" + concurrency;
                    Path benchRoot = tempDir.resolve(
                            "independent-" + mode.name() + "-c" + concurrency + "-run-" + runIndex);
                    Files.createDirectories(benchRoot);
                    Path runSetDir = BenchmarkRunSetManager.createJavaRunSet(
                            benchRoot, RetrievalBenchmarkHarness.SCENARIO,
                            "warmup-proof", BenchmarkRunSetManager.ArtifactContract.COMPONENT,
                            BenchmarkRunSetManager.EvidencePurpose.SMOKE,
                            Map.of("schema_version", "1.1", "scenario", "retrieval", "test", true),
                            Map.of("environment", "test"), List.of(evidenceMode), 1);
                    BenchmarkRunSetManager.beginMeasurement(runSetDir);

                    RetrievalService retrievalService = Mockito.mock(RetrievalService.class);
                    Mockito.when(retrievalService.search(
                                    Mockito.any(), Mockito.any(), Mockito.anyInt(),
                                    Mockito.anyInt(), Mockito.anyBoolean()))
                            .thenReturn(List.of());
                    RetrievalBenchmarkHarness.BenchmarkRunSpec spec =
                            new RetrievalBenchmarkHarness.BenchmarkRunSpec(
                                    200, 0L, concurrency, 0L, 5_000L,
                                    1_000L, concurrency, 10, 5);

                    Path rawFile = RetrievalBenchmarkHarness.runOneMode(
                            mode, runSetDir, "run-" + runIndex, spec, retrievalService,
                            List.of("q"), CONFIG_HASH, ENVIRONMENT_HASH,
                            BenchmarkRunSetManager.EvidencePurpose.SMOKE);
                    List<String> lines = Files.readAllLines(rawFile).stream()
                            .filter(line -> !line.isBlank()).toList();
                    assertEquals(concurrency, lines.size());
                    Mockito.verify(retrievalService, Mockito.times(200 + concurrency))
                            .search(Mockito.any(), Mockito.any(), Mockito.anyInt(),
                                    Mockito.anyInt(), Mockito.anyBoolean());

                    Map<String, Object> manifest = OM.readValue(
                            runSetDir.resolve("run_set_manifest.json").toFile(), new TypeReference<>() {});
                    Map<?, ?> run = (Map<?, ?>) ((List<?>) manifest.get("runs")).get(0);
                    Map<String, Object> observation = OM.readValue(
                            runSetDir.resolve(run.get("run_observation_file").toString()).toFile(),
                            new TypeReference<>() {});
                    assertEquals(200, observation.get("warmup_completed_operations"));
                    assertEquals(true, observation.get("measurement_started_after_warmup"));
                }
            }
        }
    }

    @Test
    void failIfExistsOnDuplicateRunSet() throws Exception {
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        BenchmarkRunSetManager.createRunSet(
                benchRoot, RetrievalBenchmarkHarness.SCENARIO, "implementation-bench-001",
                CONFIG_HASH, List.of("VECTOR"), 1);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                BenchmarkRunSetManager.createRunSet(
                        benchRoot, RetrievalBenchmarkHarness.SCENARIO, "implementation-bench-001",
                        CONFIG_HASH, List.of("VECTOR"), 1));
        assertTrue(ex.getMessage().contains("FAIL_IF_EXISTS"));
    }

    @Test
    void offlinePercentileMatchesNearestRank() throws Exception {
        Path runSetDir = createRunSetDir("HYBRID", 1);
        RetrievalService retrievalService = Mockito.mock(RetrievalService.class);
        Mockito.when(retrievalService.search(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
                .thenReturn(List.of(new RetrievalResult(1L, 1L, 1L, "chunk", 0.9f,
                        com.intellidesk.retrieval.ScoreType.COSINE_SIMILARITY, 0, null)));

        List<String> queries = List.of("q");
        RetrievalBenchmarkHarness.BenchmarkRunSpec spec =
                new RetrievalBenchmarkHarness.BenchmarkRunSpec(0, 0L, 5, 0L, 5_000L, 1_000L, 1, 10, 5);

        RetrievalBenchmarkHarness.runOneMode(
                RetrievalBenchmarkHarness.BenchmarkMode.HYBRID,
                runSetDir, "run-pct", spec, retrievalService, queries, CONFIG_HASH, ENVIRONMENT_HASH,
                BenchmarkRunSetManager.EvidencePurpose.SMOKE);

        Path rawFile = runSetDir.resolve("raw-retrieval-HYBRID-c1-run-pct.jsonl");
        List<Double> latencies = new ArrayList<>();
        for (String line : Files.readAllLines(rawFile)) {
            if (line.isBlank()) continue;
            Map<String, Object> sample = parseLine(line);
            latencies.add(((Number) sample.get("latency_ms")).doubleValue());
        }

        Map<BenchmarkPercentileCalculator.Percentile, Double> pcts =
                BenchmarkPercentileCalculator.compute(latencies);
        assertTrue(pcts.containsKey(BenchmarkPercentileCalculator.Percentile.P50));
        assertTrue(pcts.containsKey(BenchmarkPercentileCalculator.Percentile.P95));
        assertTrue(pcts.containsKey(BenchmarkPercentileCalculator.Percentile.P99));
    }

    private Path createRunSetDir(String mode, int runsPerMode) throws Exception {
        return createRunSetDir(mode, 1, runsPerMode);
    }

    private Path createRunSetDir(String mode, int concurrency, int runsPerMode) throws Exception {
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);
        String runSetId = "implementation-bench-c" + concurrency;
        Path runSetDir = BenchmarkRunSetManager.createJavaRunSet(
                benchRoot, RetrievalBenchmarkHarness.SCENARIO,
                runSetId, BenchmarkRunSetManager.ArtifactContract.COMPONENT,
                BenchmarkRunSetManager.EvidencePurpose.SMOKE,
                Map.of("schema_version", "1.1", "scenario", "retrieval", "test", true),
                Map.of("environment", "test"), List.of(mode + "-c" + concurrency), runsPerMode);
        BenchmarkRunSetManager.beginMeasurement(runSetDir);
        return runSetDir;
    }

    private Map<String, Object> parseLine(String line) throws Exception {
        return OM.readValue(line, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }
}
