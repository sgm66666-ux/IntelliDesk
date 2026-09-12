package com.intellidesk.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.evaluation.EvalHashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Evaluation-only benchmark namespace manager.
 *
 * <p>The legacy {@link #createRunSet} entry point remains for historical tooling
 * tests. New Java Component/B-class candidates must use
 * {@link #createJavaRunSet}; their measurement lifecycle stops at PARTIAL. The
 * Python offline finalizer is the only writer allowed to add authoritative
 * percentile links and transition an amended Java run-set to COMPLETE.
 */
public final class BenchmarkRunSetManager {

    public static final String EVIDENCE_CLASS = "bench";
    public static final String RUN_SET_MANIFEST = "run_set_manifest.json";
    public static final String JAVA_SCHEMA_VERSION = "1.1";
    public static final String FROZEN_CONFIG_FILE = "frozen_config.json";
    public static final String CLEANUP_OBSERVATION_FILE = "cleanup_observation.json";
    public static final String PREFLIGHT_OBSERVATION_FILE = "preflight_observation.json";

    private static final ObjectMapper OM = new ObjectMapper();

    private BenchmarkRunSetManager() {
    }

    public enum Status {
        INITIALIZING,
        PARTIAL,
        FAILED,
        COMPLETE
    }

    public enum ArtifactContract {
        COMPONENT,
        B_CLASS
    }

    public enum EvidencePurpose {
        FORMAL_BENCHMARK_CANDIDATE,
        SMOKE
    }

    /** Legacy schema-1.0 constructor retained for historical tooling tests. */
    public static Path createRunSet(Path benchRoot, String scenario, String runSetId,
                                    String configHash, List<String> expectedModes,
                                    int expectedRunsPerMode) throws IOException {
        validateCreateArguments(scenario, runSetId, configHash, expectedModes, expectedRunsPerMode);
        Path runSetDir = createNamespace(benchRoot, scenario, runSetId);
        Map<String, Object> manifest = baseManifest(
                "1.0", scenario, runSetId, configHash, expectedModes, expectedRunsPerMode);
        createJson(runSetDir.resolve(RUN_SET_MANIFEST), manifest);
        return runSetDir;
    }

    /** Creates a schema-1.1 Java run-set and persists its hash sources locally. */
    public static Path createJavaRunSet(
            Path benchRoot,
            String scenario,
            String runSetId,
            ArtifactContract contract,
            EvidencePurpose evidencePurpose,
            Map<String, Object> performanceConfig,
            Map<String, Object> environmentIdentity,
            List<String> expectedModes,
            int expectedRunsPerMode) throws IOException {

        if (contract == null || evidencePurpose == null) {
            throw new IllegalArgumentException("contract and evidencePurpose must not be null");
        }
        if (performanceConfig == null || performanceConfig.isEmpty()) {
            throw new IllegalArgumentException("performanceConfig must not be empty");
        }
        if (environmentIdentity == null || environmentIdentity.isEmpty()) {
            throw new IllegalArgumentException("environmentIdentity must not be empty");
        }

        String configHash = canonicalHash(performanceConfig);
        String environmentHash = canonicalHash(environmentIdentity);
        validateCreateArguments(scenario, runSetId, configHash, expectedModes, expectedRunsPerMode);

        Map<String, Object> frozenConfig = new LinkedHashMap<>();
        frozenConfig.put("schema_version", JAVA_SCHEMA_VERSION);
        frozenConfig.put("scenario", scenario);
        frozenConfig.put("config_hash", configHash);
        frozenConfig.put("config_id", configId(configHash));
        frozenConfig.put("environment_hash", environmentHash);
        frozenConfig.put("frozen_at", Instant.now().toString());
        frozenConfig.put("canonicalization", "sorted-compact-json-utf8-v1");
        frozenConfig.put("application_identity", Map.of("name", "IntelliDesk", "module", "backend"));
        frozenConfig.put("environment_identity", environmentIdentity);
        frozenConfig.put("performance_config", performanceConfig);
        BenchmarkConfigFreezer.assertNoSecrets(frozenConfig, "Java benchmark run-set freeze");
        Path runSetDir = createNamespace(benchRoot, scenario, runSetId);
        createJson(runSetDir.resolve(FROZEN_CONFIG_FILE), frozenConfig);

        Map<String, Object> manifest = baseManifest(
                JAVA_SCHEMA_VERSION, scenario, runSetId, configHash, expectedModes, expectedRunsPerMode);
        manifest.put("artifact_contract", contract.name());
        manifest.put("evidence_purpose", evidencePurpose.name());
        manifest.put("environment_hash", environmentHash);
        manifest.put("frozen_config_file", FROZEN_CONFIG_FILE);
        manifest.put("frozen_config_sha256", sha256(runSetDir.resolve(FROZEN_CONFIG_FILE)));
        manifest.put("percentile_authority", "python-offline-nearest-rank");
        manifest.put("runs", new ArrayList<>());
        createJson(runSetDir.resolve(RUN_SET_MANIFEST), manifest);
        return runSetDir;
    }

    public static void beginMeasurement(Path runSetDir) throws IOException {
        Map<String, Object> manifest = readManifest(runSetDir);
        requireJavaSchema(manifest);
        requireStatus(manifest, Status.INITIALIZING);
        manifest.put("status", Status.PARTIAL.name());
        manifest.put("measurement_started_at", Instant.now().toString());
        replaceJson(runSetDir.resolve(RUN_SET_MANIFEST), manifest);
    }

    /** Records fail-closed, explicitly non-benchmark preflight before the measured lifecycle starts. */
    public static synchronized void recordPreflight(
            Path runSetDir, Map<String, Object> observation) throws IOException {
        Map<String, Object> manifest = readManifest(runSetDir);
        requireJavaSchema(manifest);
        requireStatus(manifest, Status.INITIALIZING);

        Map<String, Object> persisted = new LinkedHashMap<>();
        if (observation != null) {
            persisted.putAll(observation);
        }
        persisted.put("schema_version", JAVA_SCHEMA_VERSION);
        persisted.put("scenario", manifest.get("scenario"));
        persisted.put("run_set_id", manifest.get("run_set_id"));
        persisted.put("classification", "NOT_BENCHMARK_EVIDENCE");
        persisted.put("formal_percentile_use_prohibited", true);
        persisted.put("observed_at", Instant.now().toString());
        Path target = runSetDir.resolve(PREFLIGHT_OBSERVATION_FILE);
        createJson(target, persisted);
        manifest.put("preflight_observation_file", PREFLIGHT_OBSERVATION_FILE);
        manifest.put("preflight_observation_sha256", sha256(target));
        replaceJson(runSetDir.resolve(RUN_SET_MANIFEST), manifest);
    }

    /** Writes one non-authoritative run observation and links final raw bytes. */
    @SuppressWarnings("unchecked")
    public static synchronized void recordRunObservation(
            Path runSetDir,
            String mode,
            String runId,
            Path rawFile,
            Map<String, Object> observation) throws IOException {

        Map<String, Object> manifest = readManifest(runSetDir);
        requireJavaSchema(manifest);
        requireStatus(manifest, Status.PARTIAL);
        if (!rawFile.normalize().getParent().equals(runSetDir.normalize())) {
            throw new IllegalArgumentException("raw file must be inside run-set directory");
        }
        if (!Files.exists(rawFile) || Files.size(rawFile) == 0) {
            throw new IllegalStateException("raw file missing or empty: " + rawFile);
        }

        String safeMode = safeIdentity(mode, "mode");
        String safeRunId = safeIdentity(runId, "runId");
        String observationName = "observation-" + safeMode + "-" + safeRunId + ".json";
        Path observationPath = runSetDir.resolve(observationName);

        Map<String, Object> persisted = new LinkedHashMap<>();
        if (observation != null) {
            persisted.putAll(observation);
        }
        persisted.put("schema_version", JAVA_SCHEMA_VERSION);
        persisted.put("scenario", manifest.get("scenario"));
        persisted.put("run_set_id", manifest.get("run_set_id"));
        persisted.put("mode", mode);
        persisted.put("run_id", runId);
        persisted.put("raw_file", rawFile.getFileName().toString());
        persisted.put("observed_at", Instant.now().toString());
        createJson(observationPath, persisted);

        List<Map<String, Object>> runs = (List<Map<String, Object>>) manifest.get("runs");
        boolean duplicate = runs.stream().anyMatch(run -> mode.equals(run.get("mode"))
                && runId.equals(run.get("run_id")));
        if (duplicate) {
            throw new IllegalStateException("duplicate run observation: " + mode + "/" + runId);
        }
        Map<String, Object> runLink = new LinkedHashMap<>();
        runLink.put("mode", mode);
        runLink.put("run_id", runId);
        runLink.put("raw_file", rawFile.getFileName().toString());
        runLink.put("raw_sha256", sha256(rawFile));
        runLink.put("run_observation_file", observationName);
        runLink.put("run_observation_sha256", sha256(observationPath));
        runs.add(runLink);
        replaceJson(runSetDir.resolve(RUN_SET_MANIFEST), manifest);
    }

    /** Records mandatory harness-owned cleanup before Python finalization. */
    public static synchronized void recordCleanup(
            Path runSetDir, boolean success, Map<String, Object> facts) throws IOException {
        Map<String, Object> manifest = readManifest(runSetDir);
        requireJavaSchema(manifest);
        requireStatus(manifest, Status.PARTIAL);

        Map<String, Object> cleanup = new LinkedHashMap<>();
        cleanup.put("schema_version", JAVA_SCHEMA_VERSION);
        cleanup.put("run_set_id", manifest.get("run_set_id"));
        cleanup.put("success", success);
        cleanup.put("completed_at", Instant.now().toString());
        cleanup.put("facts", facts == null ? Map.of() : facts);
        Path cleanupPath = runSetDir.resolve(CLEANUP_OBSERVATION_FILE);
        createJson(cleanupPath, cleanup);
        manifest.put("cleanup_observation_file", CLEANUP_OBSERVATION_FILE);
        manifest.put("cleanup_observation_sha256", sha256(cleanupPath));
        manifest.put("measurement_completed_at", Instant.now().toString());
        replaceJson(runSetDir.resolve(RUN_SET_MANIFEST), manifest);
        if (!success) {
            updateStatus(runSetDir, Status.FAILED, "mandatory harness cleanup failed");
        }
    }

    /** Persists and links sanitized live runtime provenance before cleanup. */
    public static synchronized void recordRuntimeProvenance(
            Path runSetDir, Map<String, Object> provenance) throws IOException {
        Map<String, Object> manifest = readManifest(runSetDir);
        requireJavaSchema(manifest);
        requireStatus(manifest, Status.PARTIAL);
        Path target = runSetDir.resolve("runtime_provenance.json");
        Map<String, Object> persisted = new LinkedHashMap<>();
        if (provenance != null) {
            persisted.putAll(provenance);
        }
        persisted.put("schema_version", JAVA_SCHEMA_VERSION);
        persisted.put("scenario", manifest.get("scenario"));
        persisted.put("run_set_id", manifest.get("run_set_id"));
        createJson(target, persisted);
        manifest.put("runtime_provenance_file", target.getFileName().toString());
        manifest.put("runtime_provenance_sha256", sha256(target));
        replaceJson(runSetDir.resolve(RUN_SET_MANIFEST), manifest);
    }

    public static void updateStatus(Path runSetDir, Status status, String reason) throws IOException {
        Map<String, Object> manifest = readManifest(runSetDir);
        if (JAVA_SCHEMA_VERSION.equals(manifest.get("schema_version")) && status == Status.COMPLETE) {
            throw new IllegalStateException(
                    "schema-1.1 Java COMPLETE is owned by the Python offline finalizer");
        }
        if (status == Status.COMPLETE) {
            validateLegacyRunSetComplete(runSetDir, manifest);
        }
        Status current = Status.valueOf(String.valueOf(manifest.get("status")));
        if ((current == Status.COMPLETE || current == Status.FAILED) && current != status) {
            throw new IllegalStateException("terminal run-set is immutable: " + current);
        }
        manifest.put("status", status.name());
        if (reason != null && !reason.isBlank()) {
            manifest.put("status_reason", reason);
        }
        if (status == Status.COMPLETE || status == Status.FAILED) {
            manifest.put("completed_at", Instant.now().toString());
        }
        replaceJson(runSetDir.resolve(RUN_SET_MANIFEST), manifest);
    }

    public static void assertWritable(Path runSetDir) throws IOException {
        if (!Files.exists(runSetDir.resolve(RUN_SET_MANIFEST))) {
            return;
        }
        Status status = readStatus(runSetDir);
        if (status == Status.COMPLETE || status == Status.FAILED) {
            throw new IllegalStateException("terminal run-set is immutable: " + status);
        }
    }

    public static Status readStatus(Path runSetDir) throws IOException {
        return Status.valueOf(String.valueOf(readManifest(runSetDir).get("status")));
    }

    public static Map<String, Object> readManifest(Path runSetDir) throws IOException {
        return OM.readValue(runSetDir.resolve(RUN_SET_MANIFEST).toFile(), LinkedHashMap.class);
    }

    public static String canonicalHash(Map<String, Object> value) {
        return EvalHashing.sha256Hex(EvalHashing.canonicalJson(value));
    }

    public static String configId(String configHash) {
        if (configHash == null || !configHash.matches("^[0-9a-fA-F]{64}$")) {
            throw new IllegalArgumentException("configHash must be 64 hexadecimal characters");
        }
        return configHash.substring(0, 16).toLowerCase();
    }

    public static String sha256(Path path) throws IOException {
        return EvalHashing.sha256Hex(Files.readAllBytes(path));
    }

    private static Map<String, Object> baseManifest(
            String schemaVersion, String scenario, String runSetId, String configHash,
            List<String> expectedModes, int expectedRunsPerMode) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema_version", schemaVersion);
        manifest.put("evidence_class", EVIDENCE_CLASS);
        manifest.put("scenario", scenario);
        manifest.put("config_hash", configHash);
        manifest.put("config_id", configId(configHash));
        manifest.put("run_set_id", runSetId);
        manifest.put("expected_modes", expectedModes);
        manifest.put("expected_runs_per_mode", expectedRunsPerMode);
        manifest.put("status", Status.INITIALIZING.name());
        manifest.put("created_at", Instant.now().toString());
        return manifest;
    }

    private static void validateCreateArguments(
            String scenario, String runSetId, String configHash,
            List<String> expectedModes, int expectedRunsPerMode) {
        safeIdentity(scenario, "scenario");
        safeIdentity(runSetId, "runSetId");
        configId(configHash);
        if (expectedModes == null || expectedModes.isEmpty()) {
            throw new IllegalArgumentException("expectedModes must not be empty");
        }
        if (expectedModes.stream().distinct().count() != expectedModes.size()) {
            throw new IllegalArgumentException("expectedModes must be unique");
        }
        expectedModes.forEach(mode -> safeIdentity(mode, "mode"));
        if (expectedRunsPerMode < 1) {
            throw new IllegalArgumentException("expectedRunsPerMode must be >= 1");
        }
    }

    private static Path createNamespace(Path benchRoot, String scenario, String runSetId) throws IOException {
        Path runSetDir = benchRoot.resolve(scenario).resolve(runSetId);
        if (Files.exists(runSetDir)) {
            throw new IllegalStateException("FAIL_IF_EXISTS: run-set directory already exists: " + runSetDir);
        }
        Files.createDirectories(runSetDir);
        return runSetDir;
    }

    @SuppressWarnings("unchecked")
    private static void validateLegacyRunSetComplete(Path runSetDir, Map<String, Object> manifest)
            throws IOException {
        Object modesObject = manifest.get("expected_modes");
        if (!(modesObject instanceof List<?>) || ((List<?>) modesObject).isEmpty()) {
            throw new IllegalStateException("run-set manifest missing or empty expected_modes");
        }
        List<String> modes = (List<String>) modesObject;
        int runs = ((Number) manifest.get("expected_runs_per_mode")).intValue();
        String scenario = String.valueOf(manifest.get("scenario"));
        for (String mode : modes) {
            int count = countRawFilesForMode(runSetDir, scenario, mode);
            if (count == 0) {
                throw new IllegalStateException("missing raw files for mode: " + mode);
            }
            if (count != runs) {
                throw new IllegalStateException(
                        "mode " + mode + " has " + count + " raw files, expected " + runs);
            }
        }
    }

    private static int countRawFilesForMode(Path runSetDir, String scenario, String mode) throws IOException {
        String prefix = "raw-" + scenario + "-" + mode + "-";
        try (var stream = Files.list(runSetDir)) {
            return (int) stream.filter(path -> path.getFileName().toString().startsWith(prefix)
                            && path.getFileName().toString().endsWith(".jsonl"))
                    .filter(path -> {
                        try {
                            return Files.size(path) > 0;
                        } catch (IOException e) {
                            return false;
                        }
                    }).count();
        }
    }

    private static void requireJavaSchema(Map<String, Object> manifest) {
        if (!JAVA_SCHEMA_VERSION.equals(manifest.get("schema_version"))) {
            throw new IllegalStateException("not an amended Java run-set");
        }
    }

    private static void requireStatus(Map<String, Object> manifest, Status expected) {
        Status actual = Status.valueOf(String.valueOf(manifest.get("status")));
        if (actual != expected) {
            throw new IllegalStateException("run-set status=" + actual + ", expected " + expected);
        }
    }

    private static String safeIdentity(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (!value.matches("^[A-Za-z0-9][A-Za-z0-9._-]*$")) {
            throw new IllegalArgumentException(field + " contains unsafe characters: " + value);
        }
        return value;
    }

    private static void createJson(Path target, Object value) throws IOException {
        if (Files.exists(target)) {
            throw new IllegalStateException("FAIL_IF_EXISTS: " + target);
        }
        atomicMoveJson(target, value, false);
    }

    private static void replaceJson(Path target, Object value) throws IOException {
        atomicMoveJson(target, value, true);
    }

    private static void atomicMoveJson(Path target, Object value, boolean replace) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.write(tmp, OM.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
        try {
            if (replace) {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException e) {
            if (replace) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(tmp, target);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
