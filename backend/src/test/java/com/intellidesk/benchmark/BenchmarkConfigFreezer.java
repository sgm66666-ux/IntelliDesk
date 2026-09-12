package com.intellidesk.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.intellidesk.evaluation.EvalHashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Benchmark config freeze utility.
 *
 * <p>Captures the canonical benchmark parameters (scenario, target endpoint,
 * duration, VUs, thresholds, etc.) and persists them as a frozen config artifact
 * together with a SHA-256 hash. The hash becomes the scenario config hash used
 * by {@link BenchmarkRunSetManager}.
 */
public class BenchmarkConfigFreezer {

    private static final ObjectMapper OM = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /**
     * Builds a canonical benchmark config map.
     *
     * @param scenario      scenario name
     * @param target        target endpoint URL
     * @param duration      duration string (e.g. "30s")
     * @param vus           number of virtual users
     * @param thresholds    map of threshold name to threshold value
     * @param extraMetadata optional extra metadata
     * @return canonical config map
     */
    public static Map<String, Object> buildConfig(String scenario, String target, String duration,
                                                  int vus, Map<String, Object> thresholds,
                                                  Map<String, Object> extraMetadata) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("schema_version", "1.0");
        config.put("scenario", scenario);
        config.put("target", target);
        config.put("duration", duration);
        config.put("vus", vus);
        config.put("thresholds", thresholds != null ? new LinkedHashMap<>(thresholds) : new LinkedHashMap<>());
        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            config.put("metadata", new LinkedHashMap<>(extraMetadata));
        }
        return config;
    }

    /**
     * Computes the canonical SHA-256 hash of a benchmark config.
     */
    public static String computeHash(Map<String, Object> config) throws IOException {
        String canonical = OM.writeValueAsString(config);
        return EvalHashing.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Freezes a benchmark config to a file.
     *
     * @param config     canonical config map
     * @param targetFile destination freeze file
     * @return the config hash
     */
    public static String freeze(Map<String, Object> config, Path targetFile) throws IOException {
        String hash = computeHash(config);
        Map<String, Object> freeze = new LinkedHashMap<>();
        freeze.put("schema_version", "1.0");
        freeze.put("scenario", config.get("scenario"));
        freeze.put("config_hash", hash);
        freeze.put("config_id", BenchmarkRunSetManager.configId(hash));
        freeze.put("frozen_at", Instant.now().toString());
        freeze.put("config", config);

        Files.createDirectories(targetFile.getParent());
        Files.write(targetFile, OM.writeValueAsBytes(freeze), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        return hash;
    }

    /**
     * Builds a canonical benchmark performance config map.
     *
     * @param scenario                   scenario identity
     * @param target                     target endpoint URL
     * @param httpMethod                 HTTP method
     * @param requestBodyIdentity        request body identity (nullable)
     * @param duration                   duration string (e.g. "30s")
     * @param vus                        number of virtual users
     * @param warmupPolicy               warmup policy description
     * @param iterationRatePolicy        iteration/rate policy
     * @param timeoutMs                  request timeout in milliseconds
     * @param thresholds                 map of threshold name to threshold value
     * @param expectedStatus             expected HTTP status code
     * @param successSemantics           success semantics description
     * @param sampleCollectionSemantics  sample collection semantics
     * @param percentileMethod           percentile method (e.g. "nearest-rank")
     * @param extraMetadata              optional extra metadata
     * @return canonical performance config map
     */
    public static Map<String, Object> buildPerformanceConfig(String scenario, String target, String httpMethod,
                                                             String requestBodyIdentity, String duration, int vus,
                                                             String warmupPolicy, String iterationRatePolicy,
                                                             int timeoutMs, Map<String, Object> thresholds,
                                                             int expectedStatus, String successSemantics,
                                                             String sampleCollectionSemantics, String percentileMethod,
                                                             Map<String, Object> extraMetadata) {
        return buildPerformanceConfig(scenario, target, httpMethod, requestBodyIdentity, duration, vus,
                warmupPolicy, iterationRatePolicy, timeoutMs, thresholds, expectedStatus, successSemantics,
                sampleCollectionSemantics, percentileMethod, "none", null, null, null, false, extraMetadata);
    }

    public static Map<String, Object> buildPerformanceConfig(String scenario, String target, String httpMethod,
                                                             String requestBodyIdentity, String duration, int vus,
                                                             String warmupPolicy, String iterationRatePolicy,
                                                             int timeoutMs, Map<String, Object> thresholds,
                                                             int expectedStatus, String successSemantics,
                                                             String sampleCollectionSemantics, String percentileMethod,
                                                             String authMode, String authSource, String contentType,
                                                             Map<String, Object> extraHeaders, boolean ttftMode,
                                                             Map<String, Object> extraMetadata) {
        return buildPerformanceConfig(scenario, target, httpMethod, requestBodyIdentity, duration, vus,
                warmupPolicy, iterationRatePolicy, timeoutMs, thresholds, expectedStatus, successSemantics,
                sampleCollectionSemantics, percentileMethod, authMode, authSource, contentType, extraHeaders,
                ttftMode, null, null, null, null, null, null, null,
                null, null, null, null, null, extraMetadata);
    }

    /**
     * Builds a v1.2-amendment canonical benchmark performance config map.
     *
     * <p>Includes TTFT topology, helper lifecycle, auth methodology, and paired
     * API-key comparison fields as hash-bound behavioral config.
     */
    public static Map<String, Object> buildPerformanceConfig(String scenario, String target, String httpMethod,
                                                             String requestBodyIdentity, String duration, int vus,
                                                             String warmupPolicy, String iterationRatePolicy,
                                                             int timeoutMs, Map<String, Object> thresholds,
                                                             int expectedStatus, String successSemantics,
                                                             String sampleCollectionSemantics, String percentileMethod,
                                                             String authMode, String authSource, String contentType,
                                                             Map<String, Object> extraHeaders, boolean ttftMode,
                                                             String ttftTopologyMode, String helperBindAddress,
                                                             Integer helperPort, String helperConcurrencyModel,
                                                             String helperReadinessPath, Integer helperStartupTimeoutMs,
                                                             Integer helperRequestTimeoutMarginMs,
                                                             String ttftT0T1SemanticsVersion,
                                                             String apiKeyBenchmarkMethodology,
                                                             String apiKeyBaselineMode, String apiKeyTestMode,
                                                             String apiKeyTargetLogicalIdentity,
                                                             Map<String, Object> extraMetadata) {
        if (scenario == null || scenario.isBlank()) {
            throw new IllegalArgumentException("scenario must not be blank");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        if (httpMethod == null || httpMethod.isBlank()) {
            throw new IllegalArgumentException("httpMethod must not be blank");
        }
        if (duration == null || duration.isBlank()) {
            throw new IllegalArgumentException("duration must not be blank");
        }
        if (vus < 1) {
            throw new IllegalArgumentException("vus must be >= 1");
        }
        if (timeoutMs < 1) {
            throw new IllegalArgumentException("timeoutMs must be >= 1");
        }
        if (successSemantics == null || successSemantics.isBlank()) {
            throw new IllegalArgumentException("successSemantics must not be blank");
        }
        if (sampleCollectionSemantics == null || sampleCollectionSemantics.isBlank()) {
            throw new IllegalArgumentException("sampleCollectionSemantics must not be blank");
        }
        if (percentileMethod == null || percentileMethod.isBlank()) {
            throw new IllegalArgumentException("percentileMethod must not be blank");
        }
        if (authMode == null || authMode.isBlank()) {
            throw new IllegalArgumentException("authMode must not be blank");
        }
        if (!List.of("none", "session_cookie", "api_key_header", "bearer_header").contains(authMode)) {
            throw new IllegalArgumentException("unsupported authMode: " + authMode);
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("schema_version", "1.0");
        config.put("scenario", scenario);
        config.put("target", target);
        config.put("http_method", httpMethod);
        if (requestBodyIdentity != null && !requestBodyIdentity.isBlank()) {
            config.put("request_body_identity", requestBodyIdentity);
        }
        config.put("duration", duration);
        config.put("vus", vus);
        config.put("warmup_policy", warmupPolicy);
        config.put("iteration_rate_policy", iterationRatePolicy);
        config.put("timeout_ms", timeoutMs);
        config.put("thresholds", thresholds != null ? new LinkedHashMap<>(thresholds) : new LinkedHashMap<>());
        config.put("expected_status", expectedStatus);
        config.put("success_semantics", successSemantics);
        config.put("sample_collection_semantics", sampleCollectionSemantics);
        config.put("percentile_method", percentileMethod);
        config.put("auth_mode", authMode);
        config.put("ttft_mode", ttftMode);
        if (authSource != null && !authSource.isBlank()) {
            config.put("auth_source", authSource);
        }
        if (contentType != null && !contentType.isBlank()) {
            config.put("content_type", contentType);
        }
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            config.put("extra_headers", new LinkedHashMap<>(extraHeaders));
        }
        if (ttftTopologyMode != null && !ttftTopologyMode.isBlank()) {
            config.put("ttft_topology_mode", ttftTopologyMode);
        }
        if (helperBindAddress != null && !helperBindAddress.isBlank()) {
            config.put("helper_bind_address", helperBindAddress);
        }
        if (helperPort != null) {
            config.put("helper_port", helperPort);
        }
        if (helperConcurrencyModel != null && !helperConcurrencyModel.isBlank()) {
            config.put("helper_concurrency_model", helperConcurrencyModel);
        }
        if (helperReadinessPath != null && !helperReadinessPath.isBlank()) {
            config.put("helper_readiness_path", helperReadinessPath);
        }
        if (helperStartupTimeoutMs != null) {
            config.put("helper_startup_timeout_ms", helperStartupTimeoutMs);
        }
        if (helperRequestTimeoutMarginMs != null) {
            config.put("helper_request_timeout_margin_ms", helperRequestTimeoutMarginMs);
        }
        if (ttftT0T1SemanticsVersion != null && !ttftT0T1SemanticsVersion.isBlank()) {
            config.put("ttft_t0_t1_semantics_version", ttftT0T1SemanticsVersion);
        }
        if (apiKeyBenchmarkMethodology != null && !apiKeyBenchmarkMethodology.isBlank()) {
            config.put("api_key_benchmark_methodology", apiKeyBenchmarkMethodology);
        }
        if (apiKeyBaselineMode != null && !apiKeyBaselineMode.isBlank()) {
            config.put("api_key_baseline_mode", apiKeyBaselineMode);
        }
        if (apiKeyTestMode != null && !apiKeyTestMode.isBlank()) {
            config.put("api_key_test_mode", apiKeyTestMode);
        }
        if (apiKeyTargetLogicalIdentity != null && !apiKeyTargetLogicalIdentity.isBlank()) {
            config.put("api_key_target_logical_identity", apiKeyTargetLogicalIdentity);
        }
        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            config.put("metadata", new LinkedHashMap<>(extraMetadata));
        }
        return config;
    }

    /**
     * Freezes a full benchmark execution config artifact with performance config,
     * environment identity, application identity, and k6 version.
     *
     * @param performanceConfig   canonical performance config map
     * @param environmentIdentity canonical environment identity map
     * @param applicationIdentity application identity map
     * @param k6Version           k6 version string
     * @param targetFile          destination freeze file
     * @return the performance config hash
     */
    public static String freezeExecutionConfig(Map<String, Object> performanceConfig,
                                               Map<String, Object> environmentIdentity,
                                               Map<String, Object> applicationIdentity,
                                               String k6Version, Path targetFile) throws IOException {
        String configHash = computeHash(performanceConfig);
        String environmentHash = environmentIdentity != null ? computeHash(environmentIdentity) : null;

        Map<String, Object> freeze = new LinkedHashMap<>();
        freeze.put("schema_version", "1.0");
        freeze.put("scenario", performanceConfig.get("scenario"));
        freeze.put("config_hash", configHash);
        freeze.put("config_id", BenchmarkRunSetManager.configId(configHash));
        freeze.put("environment_hash", environmentHash);
        freeze.put("k6_version", k6Version);
        freeze.put("frozen_at", Instant.now().toString());
        freeze.put("application_identity", applicationIdentity != null ? applicationIdentity : new LinkedHashMap<>());
        freeze.put("environment_identity", environmentIdentity != null ? environmentIdentity : new LinkedHashMap<>());
        freeze.put("performance_config", performanceConfig);

        assertNoSecrets(freeze, "execution config freeze");

        Files.createDirectories(targetFile.getParent());
        Files.write(targetFile, OM.writeValueAsBytes(freeze), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        return configHash;
    }

    /**
     * Fail-closed check: frozen config/identity must not contain secret keys or values.
     *
     * <p>Credential source identifiers (e.g. env-var names like BENCH_API_KEY_SECRET)
     * are allowed as values because they are not the secret itself. Raw secret values
     * such as "Bearer <token>", "sk-...", or "ak-..." are rejected.
     */
    public static void assertNoSecrets(Map<String, Object> value, String context) throws IOException {
        String json = OM.writeValueAsString(value);
        String lowerJson = json.toLowerCase();
        List<String> forbiddenKeys = List.of("api_key", "apikey", "access_token", "authorization",
                "password", "secret", "private_key", "credential");
        for (String key : forbiddenKeys) {
            if (lowerJson.contains("\"" + key + "\"")) {
                throw new IllegalArgumentException("refusing to freeze " + context + ": contains forbidden key " + key);
            }
        }
        if (lowerJson.contains("bearer ") || lowerJson.contains("sk-") || lowerJson.contains("ak-")) {
            throw new IllegalArgumentException("refusing to freeze " + context + ": contains suspected secret value");
        }
    }

    /**
     * Builds a canonical environment identity map.
     */
    public static Map<String, Object> buildEnvironmentIdentity(String os, String architecture, String cpuIdentity,
                                                                 Integer cpuLogicalCount, Integer cpuPhysicalCount,
                                                                 String memory, String javaVersion, String mavenVersion,
                                                                 String nodeVersion, String npmVersion, String k6Version,
                                                                 String dockerVersion, String dockerComposeVersion,
                                                                 Map<String, Object> extraEnvironment) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("os", os);
        env.put("architecture", architecture);
        env.put("cpu_identity", cpuIdentity);
        if (cpuLogicalCount != null) {
            env.put("cpu_logical_count", cpuLogicalCount);
        }
        if (cpuPhysicalCount != null) {
            env.put("cpu_physical_count", cpuPhysicalCount);
        }
        env.put("memory", memory);
        env.put("java_version", javaVersion);
        env.put("maven_version", mavenVersion);
        env.put("node_version", nodeVersion);
        env.put("npm_version", npmVersion);
        env.put("k6_version", k6Version);
        env.put("docker_version", dockerVersion);
        env.put("docker_compose_version", dockerComposeVersion);
        if (extraEnvironment != null && !extraEnvironment.isEmpty()) {
            env.put("extra", new LinkedHashMap<>(extraEnvironment));
        }
        return env;
    }
}
