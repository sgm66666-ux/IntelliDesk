package com.intellidesk.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tooling tests for Phase 8 Wave 2 benchmark harness.
 *
 * <p>Covers namespace collision / FAIL_IF_EXISTS, raw schema validation,
 * offline percentile calculation, run-set lifecycle transitions, COMPLETE
 * transition validation, and FAILED run-set retry semantics.
 * All tests use temporary directories; no Wave 1 evidence is touched.
 */
class BenchmarkHarnessToolingTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String CONFIG_HASH =
            "2ae529f6aa1ba50845890374762191cc8a41543aec3c1eca37f49a0e7cc847a7";
    private static final String ENVIRONMENT_HASH =
            "1111111111111111111111111111111111111111111111111111111111111111";

    @TempDir
    Path tempDir;

    private Path benchRoot;

    @BeforeEach
    void setUp() throws Exception {
        benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);
    }

    @AfterEach
    void tearDown() throws Exception {
        // @TempDir handles cleanup; no-op for clarity.
    }

    @Test
    void createRunSetProducesInitializingManifest() throws Exception {
        Path runSetDir = BenchmarkRunSetManager.createRunSet(
                benchRoot, "rag-pipeline", "implementation-bench-001",
                CONFIG_HASH,
                List.of("VECTOR", "HYBRID"),
                2);

        assertTrue(Files.exists(runSetDir));
        Path manifestPath = runSetDir.resolve(BenchmarkRunSetManager.RUN_SET_MANIFEST);
        assertTrue(Files.exists(manifestPath));

        Map<String, Object> manifest = OM.readValue(manifestPath.toFile(), LinkedHashMap.class);
        assertEquals("bench", manifest.get("evidence_class"));
        assertEquals("rag-pipeline", manifest.get("scenario"));
        assertEquals("implementation-bench-001", manifest.get("run_set_id"));
        assertEquals("2ae529f6aa1ba508", manifest.get("config_id"));
        assertEquals(List.of("VECTOR", "HYBRID"), manifest.get("expected_modes"));
        assertEquals(2, manifest.get("expected_runs_per_mode"));
        assertEquals("INITIALIZING", manifest.get("status"));
    }

    @Test
    void failIfExistsOnDuplicateRunSet() throws Exception {
        BenchmarkRunSetManager.createRunSet(
                benchRoot, "rag-pipeline", "implementation-bench-001",
                CONFIG_HASH,
                List.of("VECTOR"),
                1);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                BenchmarkRunSetManager.createRunSet(
                        benchRoot, "rag-pipeline", "implementation-bench-001",
                        CONFIG_HASH,
                        List.of("VECTOR"),
                        1));
        assertTrue(ex.getMessage().contains("FAIL_IF_EXISTS"));
    }

    @Test
    void amendedJavaRunSetPersistsOneAuthoritativeFreezeWrapper() throws Exception {
        Map<String, Object> performance = new LinkedHashMap<>();
        performance.put("schema_version", "1.0");
        performance.put("scenario", "rag-completion");
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("java_version", "21");

        Path runSetDir = BenchmarkRunSetManager.createJavaRunSet(
                benchRoot, "rag-completion", "java-contract-001",
                BenchmarkRunSetManager.ArtifactContract.B_CLASS,
                BenchmarkRunSetManager.EvidencePurpose.FORMAL_BENCHMARK_CANDIDATE,
                performance, environment, List.of("e2e-stub"), 3);

        Map<String, Object> manifest = BenchmarkRunSetManager.readManifest(runSetDir);
        Map<String, Object> freeze = OM.readValue(
                runSetDir.resolve(BenchmarkRunSetManager.FROZEN_CONFIG_FILE).toFile(), LinkedHashMap.class);
        assertEquals("1.1", manifest.get("schema_version"));
        assertEquals("B_CLASS", manifest.get("artifact_contract"));
        assertEquals(performance, freeze.get("performance_config"));
        assertEquals(environment, freeze.get("environment_identity"));
        assertEquals(manifest.get("config_hash"), freeze.get("config_hash"));
        assertEquals(manifest.get("environment_hash"), freeze.get("environment_hash"));
        assertEquals(Map.of("name", "IntelliDesk", "module", "backend"),
                freeze.get("application_identity"));
        assertFalse(manifest.containsKey("environment_file"));
        assertFalse(Files.exists(runSetDir.resolve("environment_identity.json")));
    }

    @Test
    void amendedJavaRunSetRejectsSecretsBeforeWritingFreeze() {
        Map<String, Object> performance = Map.of("scenario", "rag-completion");
        Map<String, Object> environment = Map.of("authorization", "Bearer should-not-be-written");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                BenchmarkRunSetManager.createJavaRunSet(
                        benchRoot, "rag-completion", "java-contract-secret-001",
                        BenchmarkRunSetManager.ArtifactContract.B_CLASS,
                        BenchmarkRunSetManager.EvidencePurpose.FORMAL_BENCHMARK_CANDIDATE,
                        performance, environment, List.of("e2e-stub"), 3));
        assertTrue(error.getMessage().contains("forbidden key authorization"));
        assertFalse(Files.exists(benchRoot.resolve("rag-completion")
                .resolve("java-contract-secret-001").resolve("frozen_config.json")));
    }

    @Test
    void amendedObservationIdentityCannotBeOverriddenByCaller() throws Exception {
        Map<String, Object> performance = Map.of("scenario", "rag-completion");
        Map<String, Object> environment = Map.of("java_version", "21");
        Path runSetDir = BenchmarkRunSetManager.createJavaRunSet(
                benchRoot, "rag-completion", "java-contract-001",
                BenchmarkRunSetManager.ArtifactContract.B_CLASS,
                BenchmarkRunSetManager.EvidencePurpose.FORMAL_BENCHMARK_CANDIDATE,
                performance, environment, List.of("e2e-stub"), 3);
        BenchmarkRunSetManager.beginMeasurement(runSetDir);
        Map<String, Object> manifest = BenchmarkRunSetManager.readManifest(runSetDir);
        BenchmarkRawWriter writer = new BenchmarkRawWriter(
                runSetDir, "rag-completion", "e2e-stub", "run-1",
                String.valueOf(manifest.get("config_hash")), String.valueOf(manifest.get("environment_hash")));
        writer.writeRawLine(Map.of("latency_ms", 1.0));

        Map<String, Object> attemptedOverride = new LinkedHashMap<>();
        attemptedOverride.put("schema_version", "0.0");
        attemptedOverride.put("scenario", "wrong");
        attemptedOverride.put("run_set_id", "wrong");
        attemptedOverride.put("mode", "wrong");
        attemptedOverride.put("run_id", "wrong");
        attemptedOverride.put("raw_file", "wrong.jsonl");
        attemptedOverride.put("warmup_completed", 1);
        BenchmarkRunSetManager.recordRunObservation(
                runSetDir, "e2e-stub", "run-1", writer.getRawFile(), attemptedOverride);

        Map<String, Object> updated = BenchmarkRunSetManager.readManifest(runSetDir);
        @SuppressWarnings("unchecked")
        Map<String, Object> link = ((List<Map<String, Object>>) updated.get("runs")).getFirst();
        Map<String, Object> observation = OM.readValue(
                runSetDir.resolve(String.valueOf(link.get("run_observation_file"))).toFile(), LinkedHashMap.class);
        assertEquals("1.1", observation.get("schema_version"));
        assertEquals("rag-completion", observation.get("scenario"));
        assertEquals("java-contract-001", observation.get("run_set_id"));
        assertEquals("e2e-stub", observation.get("mode"));
        assertEquals("run-1", observation.get("run_id"));
        assertEquals(writer.getRawFile().getFileName().toString(), observation.get("raw_file"));
    }

    @Test
    void rawAppendAfterCompleteIsRejected() throws Exception {
        Path runSetDir = BenchmarkRunSetManager.createRunSet(
                benchRoot, "rag-pipeline", "implementation-bench-001",
                CONFIG_HASH, List.of("VECTOR"), 1);
        BenchmarkRawWriter writer = new BenchmarkRawWriter(
                runSetDir, "rag-pipeline", "VECTOR", "run-1", CONFIG_HASH, ENVIRONMENT_HASH);
        writer.writeRawLine(Map.of("latency_ms", 1.0));
        BenchmarkRunSetManager.updateStatus(runSetDir, BenchmarkRunSetManager.Status.COMPLETE, "complete");

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> writer.writeRawLine(Map.of("latency_ms", 2.0)));
        assertTrue(error.getMessage().contains("terminal run-set is immutable: COMPLETE"));
        assertEquals(1, writer.countSamples());
    }

    @Test
    void namespaceCollisionAcrossScenariosIsAllowed() throws Exception {
        BenchmarkRunSetManager.createRunSet(
                benchRoot, "rag-pipeline", "implementation-bench-001",
                CONFIG_HASH,
                List.of("VECTOR"),
                1);

        Path other = BenchmarkRunSetManager.createRunSet(
                benchRoot, "api-key-auth", "implementation-bench-001",
                CONFIG_HASH,
                List.of("SIGN"),
                1);

        assertTrue(Files.exists(other));
    }

    @Test
    void lifecycleTransitions() throws Exception {
        Path runSetDir = BenchmarkRunSetManager.createRunSet(
                benchRoot, "rag-pipeline", "implementation-bench-001",
                CONFIG_HASH,
                List.of("VECTOR"),
                1);

        assertEquals(BenchmarkRunSetManager.Status.INITIALIZING, BenchmarkRunSetManager.readStatus(runSetDir));

        BenchmarkRunSetManager.updateStatus(runSetDir, BenchmarkRunSetManager.Status.PARTIAL, "first run complete");
        assertEquals(BenchmarkRunSetManager.Status.PARTIAL, BenchmarkRunSetManager.readStatus(runSetDir));

        writeSample(runSetDir, "rag-pipeline", "VECTOR", "run-1");
        BenchmarkRunSetManager.updateStatus(runSetDir, BenchmarkRunSetManager.Status.COMPLETE, "all runs complete");
        assertEquals(BenchmarkRunSetManager.Status.COMPLETE, BenchmarkRunSetManager.readStatus(runSetDir));
    }

    @Test
    void incompleteRunSetCannotBeMarkedCompleteByForce() throws Exception {
        Path runSetDir = BenchmarkRunSetManager.createRunSet(
                benchRoot, "rag-pipeline", "implementation-bench-001",
                CONFIG_HASH,
                List.of("VECTOR", "HYBRID"),
                1);

        // Mark only VECTOR done, HYBRID missing.
        writeSample(runSetDir, "rag-pipeline", "VECTOR", "run-1");
        BenchmarkRunSetManager.updateStatus(runSetDir, BenchmarkRunSetManager.Status.PARTIAL, "missing HYBRID");

        // Attempting COMPLETE must throw and must not leave manifest as COMPLETE.
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                BenchmarkRunSetManager.updateStatus(runSetDir, BenchmarkRunSetManager.Status.COMPLETE, "forced complete"));
        assertTrue(ex.getMessage().contains("missing raw files for mode: HYBRID"));

        BenchmarkRunSetManager.Status status = BenchmarkRunSetManager.readStatus(runSetDir);
        assertNotEquals(BenchmarkRunSetManager.Status.COMPLETE, status);
        assertEquals(BenchmarkRunSetManager.Status.PARTIAL, status);
    }

    @Test
    void validateCompleteRequiresExpectedRawCountPerMode() throws Exception {
        Path runSetDir = BenchmarkRunSetManager.createRunSet(
                benchRoot, "rag-pipeline", "implementation-bench-001",
                CONFIG_HASH,
                List.of("VECTOR"),
                2); // expects 2 raw files for VECTOR

        // Only 1 raw file written.
        writeSample(runSetDir, "rag-pipeline", "VECTOR", "run-1");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                BenchmarkRunSetManager.updateStatus(runSetDir, BenchmarkRunSetManager.Status.COMPLETE, "forced complete"));
        assertTrue(ex.getMessage().contains("VECTOR"));
        assertTrue(ex.getMessage().contains("raw files"));

        BenchmarkRunSetManager.Status status = BenchmarkRunSetManager.readStatus(runSetDir);
        assertNotEquals(BenchmarkRunSetManager.Status.COMPLETE, status);
    }

    @Test
    void failedRunSetRetryRequiresNewRunSetId() throws Exception {
        // First run-set fails.
        Path runSetDir1 = BenchmarkRunSetManager.createRunSet(
                benchRoot, "rag-pipeline", "implementation-bench-001",
                CONFIG_HASH,
                List.of("VECTOR"),
                1);
        writeSample(runSetDir1, "rag-pipeline", "VECTOR", "run-1");
        BenchmarkRunSetManager.updateStatus(runSetDir1, BenchmarkRunSetManager.Status.FAILED, "target unreachable");

        assertEquals(BenchmarkRunSetManager.Status.FAILED, BenchmarkRunSetManager.readStatus(runSetDir1));
        assertTrue(Files.exists(runSetDir1));

        // Reusing the same run-set-id is rejected (FAIL_IF_EXISTS).
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                BenchmarkRunSetManager.createRunSet(
                        benchRoot, "rag-pipeline", "implementation-bench-001",
                        CONFIG_HASH,
                        List.of("VECTOR"),
                        1));
        assertTrue(ex.getMessage().contains("FAIL_IF_EXISTS"));

        // Retry with a new run-set-id succeeds.
        Path runSetDir2 = BenchmarkRunSetManager.createRunSet(
                benchRoot, "rag-pipeline", "implementation-bench-002",
                CONFIG_HASH,
                List.of("VECTOR"),
                1);
        writeSample(runSetDir2, "rag-pipeline", "VECTOR", "run-1");
        BenchmarkRunSetManager.updateStatus(runSetDir2, BenchmarkRunSetManager.Status.COMPLETE, "retry complete");
        assertEquals(BenchmarkRunSetManager.Status.COMPLETE, BenchmarkRunSetManager.readStatus(runSetDir2));

        // Original FAILED run-set is preserved for evidence.
        assertEquals(BenchmarkRunSetManager.Status.FAILED, BenchmarkRunSetManager.readStatus(runSetDir1));
    }

    @Test
    void rawSchemaValidation() throws Exception {
        Path runSetDir = BenchmarkRunSetManager.createRunSet(
                benchRoot, "rag-pipeline", "implementation-bench-001",
                CONFIG_HASH,
                List.of("VECTOR"),
                1);

        BenchmarkRawWriter writer = new BenchmarkRawWriter(runSetDir, "rag-pipeline", "VECTOR", "run-1", CONFIG_HASH, ENVIRONMENT_HASH);
        BenchmarkRawSample sample = new BenchmarkRawSample(
                "2026-08-22T10:00:00Z",
                "rag-pipeline",
                "implementation-bench-001",
                "run-1",
                0,
                "bench_req_duration",
                "http://localhost/api/chat",
                123.45,
                null,
                200,
                true,
                null,
                10,
                10,
                "real",
                CONFIG_HASH,
                ENVIRONMENT_HASH,
                "k6-ref-1",
                new LinkedHashMap<>());
        writer.write(sample);

        Path rawFile = writer.getRawFile();
        assertTrue(Files.exists(rawFile));
        String line = Files.readString(rawFile).trim();
        Map<String, Object> parsed = OM.readValue(line, LinkedHashMap.class);
        assertEquals("rag-pipeline", parsed.get("scenario"));
        assertEquals("implementation-bench-001", parsed.get("run_set_id"));
        assertEquals("run-1", parsed.get("run_id"));
        assertEquals(0, parsed.get("sample_index"));
        assertEquals("bench_req_duration", parsed.get("metric_name"));
        assertEquals("http://localhost/api/chat", parsed.get("endpoint"));
        assertEquals(123.45, parsed.get("latency_ms"));
        assertTrue(parsed.containsKey("ttft_ms"));
        assertEquals(200, parsed.get("status_code"));
        assertEquals(true, parsed.get("success"));
        assertEquals(10, parsed.get("vu"));
        assertEquals(10, parsed.get("concurrency"));
        assertEquals("real", parsed.get("provider_mode"));
        assertEquals(CONFIG_HASH, parsed.get("config_hash"));
        assertEquals(ENVIRONMENT_HASH, parsed.get("environment_hash"));
        assertEquals("k6-ref-1", parsed.get("k6_sample_reference"));
    }

    @Test
    void rawSchemaMandatoryFields() throws Exception {
        Path runSetDir = BenchmarkRunSetManager.createRunSet(
                benchRoot, "rag-pipeline", "implementation-bench-001",
                CONFIG_HASH,
                List.of("VECTOR"),
                1);

        BenchmarkRawWriter writer = new BenchmarkRawWriter(runSetDir, "rag-pipeline", "VECTOR", "run-1", CONFIG_HASH, ENVIRONMENT_HASH);
        BenchmarkRawSample sample = new BenchmarkRawSample(
                "2026-08-22T10:00:00Z",
                "rag-pipeline",
                "implementation-bench-001",
                "run-1",
                0,
                "bench_req_duration",
                "http://localhost/api/chat",
                123.45,
                12.3,
                200,
                true,
                null,
                10,
                10,
                "real",
                CONFIG_HASH,
                ENVIRONMENT_HASH,
                "k6-ref-1",
                new LinkedHashMap<>(),
                1000.0,
                12345.0,
                12345.0123,
                "token");
        writer.write(sample);

        String line = Files.readString(writer.getRawFile()).trim();
        Map<String, Object> parsed = OM.readValue(line, LinkedHashMap.class);
        List<String> mandatory = List.of(
                "timestamp", "run_relative_time", "scenario", "run_set_id", "run_id",
                "sample_index", "metric_name", "endpoint", "latency_ms", "ttft_ms",
                "status_code", "success", "error", "vu", "concurrency", "provider_mode",
                "config_hash", "environment_hash", "k6_sample_reference", "execution_metadata",
                "t0_monotonic", "t1_monotonic", "qualifying_event_type");
        for (String field : mandatory) {
            assertTrue(parsed.containsKey(field), "missing mandatory field: " + field);
        }
        assertEquals(1000.0, parsed.get("run_relative_time"));
        assertEquals(12345.0, parsed.get("t0_monotonic"));
        assertEquals(12345.0123, parsed.get("t1_monotonic"));
        assertEquals("token", parsed.get("qualifying_event_type"));
    }

    @Test
    void rawFromK6RecordComputesRunRelativeTime() throws Exception {
        Map<String, Object> k6Record = new LinkedHashMap<>();
        k6Record.put("metric", "http_req_duration");
        k6Record.put("type", "Point");
        k6Record.put("timestamp", "2026-08-22T10:00:01Z");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("time", "2026-08-22T10:00:01.000Z");
        data.put("value", 42.0);
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("url", "http://localhost/api/chat");
        tags.put("status", "200");
        tags.put("success", "true");
        data.put("tags", tags);
        k6Record.put("data", data);

        BenchmarkRawSample sample = BenchmarkRawSample.fromK6Record(
                k6Record, "rag-pipeline", "implementation-bench-001", "run-1", 0, CONFIG_HASH, ENVIRONMENT_HASH,
                "2026-08-22T10:00:00.000Z");

        assertEquals(1000.0, sample.getRunRelativeTime());
    }

    @Test
    void rawFromK6RecordWithTtft() throws Exception {
        Map<String, Object> k6Record = new LinkedHashMap<>();
        k6Record.put("metric", "http_req_duration");
        k6Record.put("type", "Point");
        k6Record.put("timestamp", "2026-08-22T10:00:00Z");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("time", "2026-08-22T10:00:00Z");
        data.put("value", 42.0);
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("url", "http://localhost/api/chat/stream");
        tags.put("status", "200");
        tags.put("success", "true");
        tags.put("ttft_ms", "15.5");
        data.put("tags", tags);
        k6Record.put("data", data);

        BenchmarkRawSample sample = BenchmarkRawSample.fromK6Record(
                k6Record, "rag-pipeline", "implementation-bench-001", "run-1", 0, CONFIG_HASH, ENVIRONMENT_HASH);

        assertEquals(42.0, sample.getLatencyMs());
        assertEquals(15.5, sample.getTtftMs());
    }

    @Test
    void rawFromK6Record() throws Exception {
        Map<String, Object> k6Record = new LinkedHashMap<>();
        k6Record.put("metric", "http_req_duration");
        k6Record.put("type", "Point");
        k6Record.put("timestamp", "2026-08-22T10:00:00Z");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("time", "2026-08-22T10:00:00Z");
        data.put("value", 42.0);
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("url", "http://localhost/api/chat");
        tags.put("status", "200");
        tags.put("success", "true");
        data.put("tags", tags);
        k6Record.put("data", data);

        BenchmarkRawSample sample = BenchmarkRawSample.fromK6Record(
                k6Record, "rag-pipeline", "implementation-bench-001", "run-1", 0, CONFIG_HASH, ENVIRONMENT_HASH);

        assertEquals("rag-pipeline", sample.getScenario());
        assertEquals("implementation-bench-001", sample.getRunSetId());
        assertEquals(0, sample.getSampleIndex());
        assertEquals("http://localhost/api/chat", sample.getEndpoint());
        assertEquals(42.0, sample.getLatencyMs());
        assertEquals(200, sample.getStatusCode());
        assertTrue(sample.getSuccess());
        assertEquals(CONFIG_HASH, sample.getConfigHash());
        assertEquals(ENVIRONMENT_HASH, sample.getEnvironmentHash());
        assertEquals("2026-08-22T10:00:00Z", sample.getTimestamp());
    }

    @Test
    void rawFromK6RecordIncrementsSampleIndex() throws Exception {
        Map<String, Object> k6Record = new LinkedHashMap<>();
        k6Record.put("metric", "http_req_duration");
        k6Record.put("type", "Point");
        k6Record.put("timestamp", "2026-08-22T10:00:00Z");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("time", "2026-08-22T10:00:00Z");
        data.put("value", 10.0);
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("url", "http://localhost/api/chat");
        tags.put("status", "200");
        tags.put("success", "true");
        data.put("tags", tags);
        k6Record.put("data", data);

        BenchmarkRawSample first = BenchmarkRawSample.fromK6Record(
                k6Record, "rag-pipeline", "implementation-bench-001", "run-1", 0, CONFIG_HASH, ENVIRONMENT_HASH);
        BenchmarkRawSample second = BenchmarkRawSample.fromK6Record(
                k6Record, "rag-pipeline", "implementation-bench-001", "run-1", 1, CONFIG_HASH, ENVIRONMENT_HASH);

        assertEquals(0, first.getSampleIndex());
        assertEquals(1, second.getSampleIndex());
    }

    @Test
    void percentileNearestRank() throws Exception {
        // Sorted: 1..100
        List<Double> latencies = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            latencies.add((double) i);
        }

        Map<BenchmarkPercentileCalculator.Percentile, Double> result = BenchmarkPercentileCalculator.compute(latencies);

        // nearest-rank for n=100:
        // p50: ceil(0.50*100)=50 -> value 50
        // p90: ceil(0.90*100)=90 -> value 90
        // p95: ceil(0.95*100)=95 -> value 95
        // p99: ceil(0.99*100)=99 -> value 99
        assertEquals(50.0, result.get(BenchmarkPercentileCalculator.Percentile.P50));
        assertEquals(90.0, result.get(BenchmarkPercentileCalculator.Percentile.P90));
        assertEquals(95.0, result.get(BenchmarkPercentileCalculator.Percentile.P95));
        assertEquals(99.0, result.get(BenchmarkPercentileCalculator.Percentile.P99));
    }

    @Test
    void percentileEmptyThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkPercentileCalculator.compute(List.of()));
    }

    @Test
    void configFreezeProducesStableHash() throws Exception {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("p95", 500);
        thresholds.put("p99", 1000);

        Map<String, Object> config = BenchmarkConfigFreezer.buildPerformanceConfig(
                "rag-pipeline", "http://localhost/api/chat", "POST",
                "fixed-rag-prompt-v1", "30s", 10,
                "none", "fixed-rate", 30000, thresholds, 200,
                "status=200 and response stream completes",
                "all http_req_duration Point samples", "nearest-rank", null);

        String hash1 = BenchmarkConfigFreezer.computeHash(config);
        String hash2 = BenchmarkConfigFreezer.computeHash(config);
        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2);

        Map<String, Object> envIdentity = BenchmarkConfigFreezer.buildEnvironmentIdentity(
                "windows", "amd64", "unknown", 8, 4, "16 GB",
                "21.0.9", "3.9.16", "unknown", "unknown", "unknown",
                "unknown", "unknown", null);
        Map<String, Object> appIdentity = new LinkedHashMap<>();
        appIdentity.put("backend_commit", "unknown");

        Path freezeFile = tempDir.resolve("config_freeze.json");
        String frozenHash = BenchmarkConfigFreezer.freezeExecutionConfig(
                config, envIdentity, appIdentity, "unknown", freezeFile);
        assertEquals(hash1, frozenHash);
        assertTrue(Files.exists(freezeFile));

        Map<String, Object> freeze = OM.readValue(freezeFile.toFile(), LinkedHashMap.class);
        assertEquals("rag-pipeline", freeze.get("scenario"));
        assertEquals(hash1, freeze.get("config_hash"));
        assertEquals(BenchmarkRunSetManager.configId(hash1), freeze.get("config_id"));
        assertNotNull(freeze.get("environment_hash"));
        assertEquals("unknown", freeze.get("k6_version"));
        assertNotNull(freeze.get("environment_identity"));
        assertNotNull(freeze.get("application_identity"));
    }

    @Test
    void invalidPerformanceConfigFailClosed() {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        assertThrows(IllegalArgumentException.class, () ->
                BenchmarkConfigFreezer.buildPerformanceConfig(
                        "", "http://localhost/api/chat", "POST", null, "30s", 10,
                        "none", "fixed-rate", 30000, thresholds, 200, "ok", "all", "nearest-rank", null));
    }

    @Test
    void performanceConfigWithAuthDoesNotIncludeSecretValue() throws Exception {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("p95", 500);

        Map<String, Object> config = BenchmarkConfigFreezer.buildPerformanceConfig(
                "api-key-auth", "http://localhost/api/workspaces/1/knowledge-bases", "GET",
                null, "30s", 10,
                "none", "fixed-rate", 10000, thresholds, 200,
                "HTTP 200 on API-key-authenticated protected resource",
                "all http_req_duration Point samples", "nearest-rank",
                "api_key_header", "BENCH_API_KEY_SECRET", null, null, false, null);

        assertEquals("api_key_header", config.get("auth_mode"));
        assertEquals("BENCH_API_KEY_SECRET", config.get("auth_source"));

        String canonical = OM.writeValueAsString(config);
        assertTrue(canonical.contains("BENCH_API_KEY_SECRET"));
        assertFalse(canonical.contains("sk-"));
        assertFalse(canonical.contains("bearer "));

        String hashNoAuth = BenchmarkConfigFreezer.computeHash(
                BenchmarkConfigFreezer.buildPerformanceConfig(
                        "api-key-auth", "http://localhost/api/workspaces/1/knowledge-bases", "GET",
                        null, "30s", 10,
                        "none", "fixed-rate", 10000, new LinkedHashMap<>(thresholds), 200,
                        "HTTP 200 on API-key-authenticated protected resource",
                        "all http_req_duration Point samples", "nearest-rank", null));
        String hashWithAuth = BenchmarkConfigFreezer.computeHash(config);
        assertNotEquals(hashNoAuth, hashWithAuth);
    }

    @Test
    void environmentIdentityExcludesSecrets() throws Exception {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("p95", 500);
        Map<String, Object> config = BenchmarkConfigFreezer.buildPerformanceConfig(
                "rag-pipeline", "http://localhost/api/chat", "POST", null, "30s", 10,
                "none", "fixed-rate", 30000, thresholds, 200, "ok", "all", "nearest-rank", null);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("api_key", "sk-secret");
        extra.put("authorization", "Bearer token");

        Map<String, Object> envIdentity = BenchmarkConfigFreezer.buildEnvironmentIdentity(
                "windows", "amd64", "unknown", 8, 4, "16 GB",
                "21.0.9", "3.9.16", "unknown", "unknown", "unknown",
                "unknown", "unknown", extra);

        Map<String, Object> appIdentity = new LinkedHashMap<>();
        Path freezeFile = tempDir.resolve("secret_freeze.json");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                BenchmarkConfigFreezer.freezeExecutionConfig(config, envIdentity, appIdentity, "unknown", freezeFile));
        assertTrue(ex.getMessage().toLowerCase().contains("forbidden"));
    }

    private void writeSample(Path runSetDir, String scenario, String mode, String runId) throws Exception {
        BenchmarkRawWriter writer = new BenchmarkRawWriter(runSetDir, scenario, mode, runId, CONFIG_HASH, ENVIRONMENT_HASH);
        BenchmarkRawSample sample = new BenchmarkRawSample(
                "2026-08-22T10:00:00Z",
                scenario,
                runSetDir.getFileName().toString(),
                runId,
                0,
                "http://localhost/api/chat",
                42.0,
                null,
                200,
                true,
                null,
                CONFIG_HASH,
                ENVIRONMENT_HASH,
                "k6-ref",
                new LinkedHashMap<>());
        writer.write(sample);
    }
}
