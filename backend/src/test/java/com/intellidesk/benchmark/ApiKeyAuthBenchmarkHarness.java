package com.intellidesk.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.TestInfrastructureConfig;
import com.intellidesk.api_key.ApiKeyAuthenticationFilter;
import com.intellidesk.api_key.ApiKeyService;
import com.intellidesk.api_key.dto.CreateApiKeyRequest;
import com.intellidesk.api_key.dto.CreateApiKeyResponse;
import com.intellidesk.evaluation.EvalHashing;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import com.intellidesk.workspace.Workspace;
import com.intellidesk.workspace.WorkspaceService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 8 Wave 2 — COMPONENT-level API Key authentication benchmark harness.
 *
 * <p>Exercises the real production API key authentication path
 * ({@link ApiKeyAuthenticationFilter}) end-to-end, including prefix lookup,
 * BCrypt hash verification, status/expiry checks, owner resolution, workspace
 * membership, role/permission loading, and {@code last_used_at} update.
 *
 * <p>Uses the real services; no mock auth branches, no fixed sleeps. Secrets are
 * generated in memory only and never persisted. Raw per-sample data is written
 * through {@link BenchmarkRawWriter} / {@link BenchmarkRunSetManager} and
 * offline percentiles are computed with {@link BenchmarkPercentileCalculator}.
 *
 * <p>This harness is intentionally separate from the external k6 paired
 * comparison ({@code bearer-baseline vs api-key}).
 */
@SpringBootTest
@ActiveProfiles({"test", "bench"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(TestInfrastructureConfig.class)
public class ApiKeyAuthBenchmarkHarness {

    private static final String SCENARIO = "api-key-auth";
    private static final String MODE = "auth-component";
    private static final String DEFAULT_RUN_SET_ID = "api-key-auth-component-001";
    private static final String RUN_SET_ID_PROPERTY = "apiKeyAuthBenchRunSetId";
    private static final List<Integer> FORMAL_CONCURRENCIES = List.of(1, 4, 8);

    @Autowired
    private ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private UserService userService;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private com.intellidesk.retrieval.RetrievalService retrievalService;

    @TempDir
    private Path tempDir;

    private User benchUser;
    private Workspace benchWorkspace;
    private Long apiKeyId;
    private String keyPrefix;
    private String fullKey;

    /**
     * Harness run configuration. Defaults match the approved Wave 2 parameters;
     * {@link #smoke()} provides values suitable for fast unit/integration smoke.
     */
    public static final class Config {
        private final int warmupSamples;
        private final int minMeasuredSamples;
        private final long minDurationMs;
        private final long maxDurationMs;
        private final int runs;
        private final int concurrency;
        private final long requestTimeoutMs;
        private final String runSetId;

        public Config(int warmupSamples, int minMeasuredSamples, long minDurationMs, long maxDurationMs,
                      int runs, int concurrency, long requestTimeoutMs, String runSetId) {
            this.warmupSamples = warmupSamples;
            this.minMeasuredSamples = minMeasuredSamples;
            this.minDurationMs = minDurationMs;
            this.maxDurationMs = maxDurationMs;
            this.runs = runs;
            this.concurrency = concurrency;
            this.requestTimeoutMs = requestTimeoutMs;
            this.runSetId = runSetId;
        }

        public static Config defaults() {
            return new Config(200, 1000, 5_000L, 60_000L, 3, 1, 30_000L,
                    DEFAULT_RUN_SET_ID + "-" + Instant.now().toEpochMilli());
        }

        public static Config smoke() {
            return new Config(2, 5, 0L, 5_000L, 1, 1, 30_000L,
                    "api-key-auth-smoke-" + UUID.randomUUID());
        }

        public Config withWarmup(int warmupSamples) {
            return new Config(warmupSamples, minMeasuredSamples, minDurationMs, maxDurationMs,
                    runs, concurrency, requestTimeoutMs, runSetId);
        }

        public Config withMinSamples(int minMeasuredSamples) {
            return new Config(warmupSamples, minMeasuredSamples, minDurationMs, maxDurationMs,
                    runs, concurrency, requestTimeoutMs, runSetId);
        }

        public Config withMinDurationMs(long minDurationMs) {
            return new Config(warmupSamples, minMeasuredSamples, minDurationMs, maxDurationMs,
                    runs, concurrency, requestTimeoutMs, runSetId);
        }

        public Config withRuns(int runs) {
            return new Config(warmupSamples, minMeasuredSamples, minDurationMs, maxDurationMs,
                    runs, concurrency, requestTimeoutMs, runSetId);
        }

        public Config withConcurrency(int concurrency) {
            return new Config(warmupSamples, minMeasuredSamples, minDurationMs, maxDurationMs,
                    runs, concurrency, requestTimeoutMs, runSetId);
        }

        public Config withRunSetId(String runSetId) {
            return new Config(warmupSamples, minMeasuredSamples, minDurationMs, maxDurationMs,
                    runs, concurrency, requestTimeoutMs, runSetId);
        }
    }

    public static final class RunResult {
        private final List<Double> latencies;
        private final int successes;
        private final int failures;

        public RunResult(List<Double> latencies, int successes, int failures) {
            this.latencies = latencies;
            this.successes = successes;
            this.failures = failures;
        }
    }

    public static final class Summary {
        private final Path runSetDir;
        private final String configHash;
        private final List<RunResult> runs;
        private final Map<BenchmarkPercentileCalculator.Percentile, Double> percentiles;

        public Summary(Path runSetDir, String configHash, List<RunResult> runs,
                       Map<BenchmarkPercentileCalculator.Percentile, Double> percentiles) {
            this.runSetDir = runSetDir;
            this.configHash = configHash;
            this.runs = runs;
            this.percentiles = percentiles;
        }
    }

    @BeforeAll
    void provision() {
        benchUser = userService.register(
                "bench-api-key-user-" + UUID.randomUUID(),
                "bench-password-" + UUID.randomUUID(),
                "bench-" + UUID.randomUUID() + "@example.com",
                "Bench User");

        benchWorkspace = workspaceService.createWorkspace(
                "bench-workspace-" + UUID.randomUUID(),
                "API Key auth benchmark workspace",
                benchUser.getId());

        CreateApiKeyRequest request = new CreateApiKeyRequest();
        request.setName("bench-api-key");
        request.setScope("READ");
        CreateApiKeyResponse response = apiKeyService.create(
                benchWorkspace.getId(), benchUser.getId(), request);

        this.apiKeyId = response.getId();
        this.keyPrefix = response.getKeyPrefix();
        this.fullKey = response.getFullKey();
    }

    @AfterAll
    void cleanup() {
        cleanupProvisionedResources();
    }

    private boolean cleanupProvisionedResources() {
        boolean success = true;
        if (apiKeyId != null) {
            success &= apiKeyService.removeById(apiKeyId);
            apiKeyId = null;
        }
        if (benchWorkspace != null) {
            try {
                workspaceService.deleteWorkspace(benchWorkspace.getId(), benchUser.getId());
            } catch (Exception error) {
                success = false;
            }
            benchWorkspace = null;
        }
        if (benchUser != null && benchUser.getId() != null) {
            try {
                jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", benchUser.getId());
                success &= userService.removeById(benchUser.getId());
            } catch (Exception error) {
                success = false;
            }
            benchUser = null;
        }
        fullKey = null;
        keyPrefix = null;
        return success;
    }

    /**
     * Resolves the project-level benchmark root ({@code docs/evaluation/bench})
     * relative to the backend working directory used by Maven.
     */
    public static Path resolveBenchRoot() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path projectRoot = cwd.getFileName().toString().equals("backend") ? cwd.getParent() : cwd;
        return projectRoot.resolve("docs").resolve("evaluation").resolve("bench");
    }

    private Map<String, Object> buildHarnessConfig(Config config) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("schema_version", "1.0");
        cfg.put("scenario", SCENARIO);
        cfg.put("mode", MODE);
        cfg.put("auth_path", "ApiKeyAuthenticationFilter.doFilterInternal");
        cfg.put("target_component", "ApiKeyAuthenticationFilter");
        cfg.put("warmup_samples", config.warmupSamples);
        cfg.put("min_measured_samples", config.minMeasuredSamples);
        cfg.put("min_duration_ms", config.minDurationMs);
        cfg.put("max_duration_ms", config.maxDurationMs);
        cfg.put("runs", config.runs);
        cfg.put("concurrency_levels", FORMAL_CONCURRENCIES);
        cfg.put("request_timeout_ms", config.requestTimeoutMs);
        cfg.put("provider_mode", "real");
        cfg.put("percentile_method", "nearest-rank");
        cfg.put("success_semantics", "HTTP 200 and SecurityContext authentication set by API Key filter");
        return cfg;
    }

    private String computeConfigHash(Config config) {
        return BenchmarkRunSetManager.canonicalHash(buildHarnessConfig(config));
    }

    private Map<String, Object> buildEnvironmentIdentity() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("os_name", System.getProperty("os.name", "unknown"));
        env.put("os_arch", System.getProperty("os.arch", "unknown"));
        env.put("java_version", System.getProperty("java.version", "unknown"));
        env.put("java_vm_name", System.getProperty("java.vm.name", "unknown"));
        env.put("available_processors", Runtime.getRuntime().availableProcessors());
        env.put("max_memory_bytes", Runtime.getRuntime().maxMemory());
        return env;
    }

    private String computeEnvironmentHash() {
        return BenchmarkRunSetManager.canonicalHash(buildEnvironmentIdentity());
    }

    /**
     * Executes the benchmark and persists raw per-sample records.
     *
     * @param benchRoot root directory for benchmark run-sets
     * @param config    harness configuration
     * @return summary of the run
     */
    public Summary execute(Path benchRoot, Config config) throws Exception {
        return execute(benchRoot, config, BenchmarkRunSetManager.EvidencePurpose.SMOKE,
                List.of(config.concurrency));
    }

    private Summary execute(Path benchRoot, Config config,
                            BenchmarkRunSetManager.EvidencePurpose evidencePurpose,
                            List<Integer> concurrencies) throws Exception {
        Map<String, Object> performanceConfig = buildHarnessConfig(config);
        Map<String, Object> environmentIdentity = buildEnvironmentIdentity();
        String configHash = BenchmarkRunSetManager.canonicalHash(performanceConfig);
        String environmentHash = BenchmarkRunSetManager.canonicalHash(environmentIdentity);
        List<String> expectedModes = concurrencies.stream().map(c -> MODE + "-c" + c).toList();

        Path runSetDir = BenchmarkRunSetManager.createJavaRunSet(
                benchRoot, SCENARIO, config.runSetId,
                BenchmarkRunSetManager.ArtifactContract.COMPONENT, evidencePurpose,
                performanceConfig, environmentIdentity, expectedModes, config.runs);
        BenchmarkRunSetManager.beginMeasurement(runSetDir);

        List<RunResult> results = new ArrayList<>();
        try {
            for (int concurrency : concurrencies) {
                Config concurrencyConfig = config.withConcurrency(concurrency);
                for (int i = 1; i <= config.runs; i++) {
                    RunResult result = runOneRun("run-" + i, runSetDir, concurrencyConfig,
                            configHash, environmentHash, evidencePurpose);
                    results.add(result);
                }
            }
        } catch (Exception e) {
            BenchmarkRunSetManager.updateStatus(runSetDir, BenchmarkRunSetManager.Status.FAILED,
                    e.getMessage());
            throw e;
        }

        List<Double> allLatencies = new ArrayList<>();
        for (RunResult r : results) {
            allLatencies.addAll(r.latencies);
        }
        Map<BenchmarkPercentileCalculator.Percentile, Double> percentiles =
                BenchmarkPercentileCalculator.compute(allLatencies);
        return new Summary(runSetDir, configHash, results, percentiles);
    }

    private RunResult runOneRun(String runId, Path runSetDir, Config config,
                                String configHash, String environmentHash,
                                BenchmarkRunSetManager.EvidencePurpose evidencePurpose) throws IOException {
        String evidenceMode = MODE + "-c" + config.concurrency;
        BenchmarkRawWriter writer = new BenchmarkRawWriter(
                runSetDir, SCENARIO, evidenceMode, runId, configHash, environmentHash);

        ExecutorService executor = Executors.newFixedThreadPool(config.concurrency);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();

        // Warmup: discard; not written to raw.
        int warmupCompleted = 0;
        while (warmupCompleted < config.warmupSamples) {
            int batchSize = Math.min(config.concurrency, config.warmupSamples - warmupCompleted);
            List<AuthOutcome> warmup = executeAuthBatch(
                    config, executor, batchSize, active, maxInFlight);
            if (warmup.stream().anyMatch(outcome -> !outcome.success)) {
                throw new IllegalStateException("API Key warmup execution failed");
            }
            warmupCompleted += batchSize;
        }

        List<Double> latencies = new ArrayList<>();
        int successes = 0;
        int failures = 0;

        long measuredStartNs = System.nanoTime();
        int sampleIndex = 0;

        try {
            while (true) {
                long elapsedMs = (System.nanoTime() - measuredStartNs) / 1_000_000L;
                if (latencies.size() >= config.minMeasuredSamples && elapsedMs >= config.minDurationMs) {
                    break;
                }
                if (elapsedMs >= config.maxDurationMs) {
                    throw new IllegalStateException("BENCH_INSUFFICIENT_SAMPLE_VOLUME: samples="
                            + latencies.size() + ", measured_duration_ms=" + elapsedMs);
                }

                List<AuthOutcome> batch = executeAuthBatch(
                        config, executor, config.concurrency, active, maxInFlight);
                for (AuthOutcome outcome : batch) {
                    double durationMs = outcome.durationNs / 1_000_000.0;
                    latencies.add(durationMs);
                    if (outcome.success) {
                        successes++;
                    } else {
                        failures++;
                    }
                    writer.write(buildSample(
                            evidenceMode, evidencePurpose, runSetDir, runId, sampleIndex, durationMs, outcome,
                            measuredStartNs, config, configHash, environmentHash));
                    sampleIndex++;
                }
            }
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("warmup_completed", warmupCompleted);
            observation.put("max_in_flight_observed", maxInFlight.get());
            BenchmarkRunSetManager.recordRunObservation(
                    runSetDir, evidenceMode, runId, writer.getRawFile(), observation);
        } finally {
            executor.shutdownNow();
        }

        return new RunResult(latencies, successes, failures);
    }

    private List<AuthOutcome> executeAuthBatch(
            Config config, ExecutorService executor, int batchSize,
            AtomicInteger active, AtomicInteger maxInFlight) {
        List<Future<AuthOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            futures.add(executor.submit(() -> {
                int inFlight = active.incrementAndGet();
                maxInFlight.accumulateAndGet(inFlight, Math::max);
                try {
                    return authenticateOnce(config);
                } finally {
                    active.decrementAndGet();
                }
            }));
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.requestTimeoutMs);
        List<AuthOutcome> outcomes = new ArrayList<>();
        for (Future<AuthOutcome> future : futures) {
            long remaining = deadline - System.nanoTime();
            try {
                if (remaining <= 0) {
                    throw new TimeoutException("batch deadline exceeded");
                }
                outcomes.add(future.get(remaining, TimeUnit.NANOSECONDS));
            } catch (TimeoutException error) {
                future.cancel(true);
                outcomes.add(new AuthOutcome(false,
                        TimeUnit.MILLISECONDS.toNanos(config.requestTimeoutMs), 500, "timeout"));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                outcomes.add(new AuthOutcome(false, 0L, 500, "interrupted"));
            } catch (ExecutionException error) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                outcomes.add(new AuthOutcome(false, 0L, 500, cause.getMessage()));
            }
        }
        return outcomes;
    }

    private static final class AuthOutcome {
        private final boolean success;
        private final long durationNs;
        private final int statusCode;
        private final String error;

        AuthOutcome(boolean success, long durationNs, int statusCode, String error) {
            this.success = success;
            this.durationNs = durationNs;
            this.statusCode = statusCode;
            this.error = error;
        }
    }

    private AuthOutcome authenticateOnce(Config config) {
        SecurityContextHolder.clearContext();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", fullKey);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        long startNs = System.nanoTime();
        try {
            apiKeyAuthenticationFilter.doFilter(request, response, chain);
        } catch (Exception e) {
            long durationNs = System.nanoTime() - startNs;
            return new AuthOutcome(false, durationNs, response.getStatus(), e.getMessage());
        }
        long durationNs = System.nanoTime() - startNs;

        boolean chainCalled = chain.getRequest() != null;
        boolean authenticated = SecurityContextHolder.getContext().getAuthentication() != null;
        boolean success = response.getStatus() == 200 && chainCalled && authenticated;
        String error = success ? null : "auth_failed_or_chain_not_invoked";
        return new AuthOutcome(success, durationNs, response.getStatus(), error);
    }

    private BenchmarkRawSample buildSample(String evidenceMode,
                                           BenchmarkRunSetManager.EvidencePurpose evidencePurpose,
                                           Path runSetDir, String runId, int sampleIndex,
                                           double durationMs, AuthOutcome outcome,
                                           long measuredStartNs, Config config,
                                           String configHash, String environmentHash) {
        String timestamp = Instant.now().toString();
        double runRelativeTime = (System.nanoTime() - measuredStartNs) / 1_000_000.0;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", MODE);
        metadata.put("description",
                "API Key prefix lookup + BCrypt hash verification + full auth decision path");
        metadata.put("auth_path", "ApiKeyAuthenticationFilter.doFilterInternal");
        metadata.put("sample_type", "measured");
        metadata.put("user_id", benchUser.getId());
        metadata.put("workspace_id", benchWorkspace.getId());
        metadata.put("api_key_id", apiKeyId);
        metadata.put("api_key_prefix", keyPrefix);
        metadata.put("request_timeout_ms", config.requestTimeoutMs);
        metadata.put("evidence_mode", evidenceMode);
        if (evidencePurpose == BenchmarkRunSetManager.EvidencePurpose.SMOKE) {
            metadata.put("evidence_tag", "NOT_BENCHMARK_EVIDENCE");
        }

        return new BenchmarkRawSample(
                timestamp,
                SCENARIO,
                runSetDir.getFileName().toString(),
                runId,
                sampleIndex,
                "auth_duration",
                "component://api-key-auth",
                durationMs,
                null,
                outcome.statusCode,
                outcome.success,
                outcome.error,
                1,
                config.concurrency,
                "real",
                configHash,
                environmentHash,
                runId + "/" + sampleIndex,
                metadata,
                runRelativeTime,
                null,
                null,
                null);
    }

    // -------------------------------------------------------------------------
    // Smoke tests
    // -------------------------------------------------------------------------

    @Test
    void formalBenchmarkWhenEnabled() throws Exception {
        if (!Boolean.getBoolean("intellidesk.benchmark.formal")) {
            return;
        }
        String runSetId = System.getProperty(RUN_SET_ID_PROPERTY);
        if (runSetId == null || runSetId.isBlank()) {
            throw new IllegalArgumentException("-D" + RUN_SET_ID_PROPERTY + " is required for formal execution");
        }
        Config config = Config.defaults().withRunSetId(runSetId.trim());
        Summary summary = execute(resolveBenchRoot(), config,
                BenchmarkRunSetManager.EvidencePurpose.FORMAL_BENCHMARK_CANDIDATE,
                FORMAL_CONCURRENCIES);
        boolean cleanupSuccess = cleanupProvisionedResources();
        BenchmarkRunSetManager.recordCleanup(summary.runSetDir, cleanupSuccess,
                Map.of("api_key_removed", true, "workspace_removed", true, "user_removed", true));
        if (!cleanupSuccess) {
            throw new IllegalStateException("mandatory API Key benchmark cleanup failed");
        }
    }

    @Test
    void smokeRunProducesCompleteRunSet() throws Exception {
        Config config = Config.smoke();
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        Summary summary = execute(benchRoot, config);

        assertNotNull(summary.runSetDir);
        assertTrue(Files.exists(summary.runSetDir));
        assertEquals(computeConfigHash(config), summary.configHash);
        assertEquals(BenchmarkRunSetManager.Status.PARTIAL,
                BenchmarkRunSetManager.readStatus(summary.runSetDir));

        Path rawFile = findRawFile(summary.runSetDir);
        assertTrue(Files.exists(rawFile));
        List<String> lines = Files.readAllLines(rawFile);
        assertFalse(lines.isEmpty());
    }

    @Test
    void warmupSamplesExcludedFromRaw() throws Exception {
        Config config = Config.smoke()
                .withWarmup(2)
                .withMinSamples(3)
                .withMinDurationMs(0L);
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        Summary summary = execute(benchRoot, config);
        Path rawFile = findRawFile(summary.runSetDir);
        List<String> lines = Files.readAllLines(rawFile);

        assertEquals(3, lines.size(), "only measured samples should be persisted");
        for (String line : lines) {
            Map<String, Object> parsed = objectMapper.readValue(line, LinkedHashMap.class);
            int sampleIndex = ((Number) parsed.get("sample_index")).intValue();
            assertTrue(sampleIndex >= 0 && sampleIndex < 3,
                    "raw indices must be measured-only and zero-based");
        }
    }

    @Test
    void rawSchemaHasMandatoryFields() throws Exception {
        Config config = Config.smoke()
                .withWarmup(1)
                .withMinSamples(2)
                .withMinDurationMs(0L);
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        Summary summary = execute(benchRoot, config);
        Path rawFile = findRawFile(summary.runSetDir);
        String firstLine = Files.readAllLines(rawFile).get(0);
        Map<String, Object> parsed = objectMapper.readValue(firstLine, LinkedHashMap.class);

        List<String> mandatory = List.of(
                "timestamp", "scenario", "run_set_id", "run_id", "sample_index",
                "metric_name", "endpoint", "latency_ms", "status_code", "success",
                "concurrency", "provider_mode", "config_hash", "environment_hash",
                "execution_metadata");
        for (String field : mandatory) {
            assertTrue(parsed.containsKey(field), "missing mandatory field: " + field);
        }

        assertEquals(SCENARIO, parsed.get("scenario"));
        assertEquals("real", parsed.get("provider_mode"));
        assertEquals(200, parsed.get("status_code"));
        assertEquals(Boolean.TRUE, parsed.get("success"));
        assertEquals(computeConfigHash(config), parsed.get("config_hash"));

        Map<String, Object> metadata = (Map<String, Object>) parsed.get("execution_metadata");
        assertNotNull(metadata);
        assertEquals(MODE, metadata.get("mode"));
        assertEquals("API Key prefix lookup + BCrypt hash verification + full auth decision path",
                metadata.get("description"));
    }

    @Test
    void rawDoesNotContainSecrets() throws Exception {
        Config config = Config.smoke()
                .withWarmup(1)
                .withMinSamples(3)
                .withMinDurationMs(0L);
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        Summary summary = execute(benchRoot, config);
        Path rawFile = findRawFile(summary.runSetDir);
        String rawText = Files.readString(rawFile).toLowerCase();

        assertFalse(rawText.contains(fullKey.toLowerCase()),
                "raw must not contain the full API key");
        assertFalse(rawText.contains("bearer "),
                "raw must not contain a bearer token");
        assertFalse(rawText.contains("sk-"),
                "raw must not contain sk- style secret");
    }

    @Test
    void configHashMatchesHarnessConfig() throws Exception {
        Config config = Config.smoke()
                .withWarmup(1)
                .withMinSamples(2)
                .withMinDurationMs(0L);
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        Summary summary = execute(benchRoot, config);
        Path rawFile = findRawFile(summary.runSetDir);
        String expectedConfigHash = computeConfigHash(config);
        String expectedEnvironmentHash = computeEnvironmentHash();

        for (String line : Files.readAllLines(rawFile)) {
            Map<String, Object> parsed = objectMapper.readValue(line, LinkedHashMap.class);
            assertEquals(expectedConfigHash, parsed.get("config_hash"));
            assertEquals(expectedEnvironmentHash, parsed.get("environment_hash"));
        }
    }

    @Test
    void failIfExistsOnDuplicateRunSet() throws Exception {
        Path benchRoot = tempDir.resolve("bench");
        Files.createDirectories(benchRoot);

        String configHash = computeConfigHash(Config.smoke());
        String runSetId = "fail-if-exists-001";

        Path first = BenchmarkRunSetManager.createRunSet(
                benchRoot, SCENARIO, runSetId, configHash, List.of(MODE), 1);
        assertTrue(Files.exists(first));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                BenchmarkRunSetManager.createRunSet(
                        benchRoot, SCENARIO, runSetId, configHash, List.of(MODE), 1));
        assertTrue(ex.getMessage().contains("FAIL_IF_EXISTS"));
    }

    private Path findRawFile(Path runSetDir) throws IOException {
        String prefix = "raw-" + SCENARIO + "-" + MODE + "-";
        try (var stream = Files.list(runSetDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith(prefix)
                            && p.getFileName().toString().endsWith(".jsonl"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("raw file not found in " + runSetDir));
        }
    }
}
