package com.intellidesk.benchmark;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable per-sample benchmark raw record.
 *
 * <p>Each record corresponds to one k6 sample / one benchmark execution sample.
 * The schema is intentionally flat and stable so that offline percentile
 * calculators can operate without parsing domain-specific nested objects.
 *
 * <p>Mandatory first-class fields (Phase 8 Wave 2 plan v2.2):
 * timestamp, run_relative_time, scenario, run_set_id, run_id, sample_index,
 * metric_name, endpoint, latency_ms, status_code, success, error, vu,
 * concurrency, provider_mode, config_hash, environment_hash,
 * k6_sample_reference, execution_metadata. TTFT samples additionally include
 * t0_monotonic, t1_monotonic, ttft_ms, qualifying_event_type.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class BenchmarkRawSample {

    private static final ObjectMapper OM = new ObjectMapper();

    private final String timestamp;
    private final Double runRelativeTime;
    private final String scenario;
    private final String runSetId;
    private final String runId;
    private final Integer sampleIndex;
    private final String metricName;
    private final String endpoint;
    private final Double latencyMs;
    private final Double ttftMs;
    private final Integer statusCode;
    private final Boolean success;
    private final String error;
    private final Integer vu;
    private final Integer concurrency;
    private final String providerMode;
    private final String configHash;
    private final String environmentHash;
    private final String k6SampleReference;
    private final Map<String, Object> executionMetadata;
    private final Double t0Monotonic;
    private final Double t1Monotonic;
    private final String qualifyingEventType;

    public BenchmarkRawSample(String timestamp, String scenario, String runSetId, String runId,
                              Integer sampleIndex, String endpoint, Double latencyMs, Double ttftMs,
                              Integer statusCode, Boolean success, String error, String configHash,
                              String environmentHash, String k6SampleReference,
                              Map<String, Object> executionMetadata) {
        this(timestamp, scenario, runSetId, runId, sampleIndex, null, endpoint, latencyMs, ttftMs,
                statusCode, success, error, null, null, null, configHash, environmentHash,
                k6SampleReference, executionMetadata, null, null, null, null);
    }

    public BenchmarkRawSample(String timestamp, String scenario, String runSetId, String runId,
                              Integer sampleIndex, String metricName, String endpoint, Double latencyMs,
                              Double ttftMs, Integer statusCode, Boolean success, String error,
                              Integer vu, Integer concurrency, String providerMode, String configHash,
                              String environmentHash, String k6SampleReference,
                              Map<String, Object> executionMetadata) {
        this(timestamp, scenario, runSetId, runId, sampleIndex, metricName, endpoint, latencyMs, ttftMs,
                statusCode, success, error, vu, concurrency, providerMode, configHash, environmentHash,
                k6SampleReference, executionMetadata, null, null, null, null);
    }

    public BenchmarkRawSample(String timestamp, String scenario, String runSetId, String runId,
                              Integer sampleIndex, String metricName, String endpoint, Double latencyMs,
                              Double ttftMs, Integer statusCode, Boolean success, String error,
                              Integer vu, Integer concurrency, String providerMode, String configHash,
                              String environmentHash, String k6SampleReference,
                              Map<String, Object> executionMetadata, Double runRelativeTime,
                              Double t0Monotonic, Double t1Monotonic, String qualifyingEventType) {
        this.timestamp = timestamp;
        this.runRelativeTime = runRelativeTime;
        this.scenario = scenario;
        this.runSetId = runSetId;
        this.runId = runId;
        this.sampleIndex = sampleIndex;
        this.metricName = metricName;
        this.endpoint = endpoint;
        this.latencyMs = latencyMs;
        this.ttftMs = ttftMs;
        this.statusCode = statusCode;
        this.success = success;
        this.error = error;
        this.vu = vu;
        this.concurrency = concurrency;
        this.providerMode = providerMode;
        this.configHash = configHash;
        this.environmentHash = environmentHash;
        this.k6SampleReference = k6SampleReference;
        this.executionMetadata = executionMetadata != null ? executionMetadata : new LinkedHashMap<>();
        this.t0Monotonic = t0Monotonic;
        this.t1Monotonic = t1Monotonic;
        this.qualifyingEventType = qualifyingEventType;
    }

    @JsonProperty("timestamp")
    public String getTimestamp() {
        return timestamp;
    }

    @JsonProperty("run_relative_time")
    public Double getRunRelativeTime() {
        return runRelativeTime;
    }

    @JsonProperty("scenario")
    public String getScenario() {
        return scenario;
    }

    @JsonProperty("run_set_id")
    public String getRunSetId() {
        return runSetId;
    }

    @JsonProperty("run_id")
    public String getRunId() {
        return runId;
    }

    @JsonProperty("sample_index")
    public Integer getSampleIndex() {
        return sampleIndex;
    }

    @JsonProperty("metric_name")
    public String getMetricName() {
        return metricName;
    }

    @JsonProperty("endpoint")
    public String getEndpoint() {
        return endpoint;
    }

    @JsonProperty("latency_ms")
    public Double getLatencyMs() {
        return latencyMs;
    }

    @JsonProperty("ttft_ms")
    public Double getTtftMs() {
        return ttftMs;
    }

    @JsonProperty("status_code")
    public Integer getStatusCode() {
        return statusCode;
    }

    @JsonProperty("success")
    public Boolean getSuccess() {
        return success;
    }

    @JsonProperty("error")
    public String getError() {
        return error;
    }

    @JsonProperty("vu")
    public Integer getVu() {
        return vu;
    }

    @JsonProperty("concurrency")
    public Integer getConcurrency() {
        return concurrency;
    }

    @JsonProperty("provider_mode")
    public String getProviderMode() {
        return providerMode;
    }

    @JsonProperty("config_hash")
    public String getConfigHash() {
        return configHash;
    }

    @JsonProperty("environment_hash")
    public String getEnvironmentHash() {
        return environmentHash;
    }

    @JsonProperty("k6_sample_reference")
    public String getK6SampleReference() {
        return k6SampleReference;
    }

    @JsonProperty("execution_metadata")
    public Map<String, Object> getExecutionMetadata() {
        return executionMetadata;
    }

    @JsonProperty("t0_monotonic")
    public Double getT0Monotonic() {
        return t0Monotonic;
    }

    @JsonProperty("t1_monotonic")
    public Double getT1Monotonic() {
        return t1Monotonic;
    }

    @JsonProperty("qualifying_event_type")
    public String getQualifyingEventType() {
        return qualifyingEventType;
    }

    /**
     * Serializes this sample to a canonical JSON line (no pretty-printing).
     */
    public byte[] toJsonLine() throws IOException {
        return OM.writeValueAsBytes(this);
    }

    /**
     * Writes this sample as a single JSON line to the given file.
     */
    public void appendTo(Path file) throws IOException {
        byte[] line = OM.writeValueAsBytes(this);
        Files.write(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.write(file, new byte[]{'\n'}, StandardOpenOption.APPEND);
    }

    /**
     * Factory for a sample derived from a k6 JSON record.
     *
     * @param k6Record one k6 JSON output line as a map
     * @param scenario scenario name
     * @param runSetId run-set id
     * @param runId    run id (e.g. "run-1")
     * @return a {@link BenchmarkRawSample}
     */
    @SuppressWarnings("unchecked")
    public static BenchmarkRawSample fromK6Record(Map<String, Object> k6Record, String scenario,
                                                  String runSetId, String runId, Integer sampleIndex,
                                                  String configHash, String environmentHash) {
        return fromK6Record(k6Record, scenario, runSetId, runId, sampleIndex, configHash, environmentHash, null);
    }

    public static BenchmarkRawSample fromK6Record(Map<String, Object> k6Record, String scenario,
                                                  String runSetId, String runId, Integer sampleIndex,
                                                  String configHash, String environmentHash,
                                                  String runStartTime) {
        String metric = (String) k6Record.get("metric");
        String type = (String) k6Record.get("type");
        String timestamp = Instant.now().toString();
        if (k6Record.containsKey("timestamp")) {
            timestamp = k6Record.get("timestamp").toString();
        }

        String endpoint = null;
        Double latencyMs = null;
        Double ttftMs = null;
        Integer statusCode = null;
        Boolean success = null;
        String error = null;
        String k6SampleReference = null;
        String metricName = null;
        Integer vu = null;
        Integer concurrency = null;
        String providerMode = null;
        Double t0Monotonic = null;
        Double t1Monotonic = null;
        String qualifyingEventType = null;
        Double runRelativeTime = null;

        Map<String, Object> executionMetadata = new LinkedHashMap<>();
        executionMetadata.put("k6_metric", metric);
        executionMetadata.put("k6_type", type);
        executionMetadata.put("k6_raw", k6Record);

        if ("Point".equals(type) && k6Record.containsKey("data")) {
            Map<String, Object> data = (Map<String, Object>) k6Record.get("data");
            k6SampleReference = String.valueOf(data.getOrDefault("time", timestamp));
            if (runStartTime != null && !runStartTime.isBlank()) {
                Double sampleEpoch = parseIsoToEpochMs(k6SampleReference);
                Double startEpoch = parseIsoToEpochMs(runStartTime);
                if (sampleEpoch != null && startEpoch != null) {
                    runRelativeTime = sampleEpoch - startEpoch;
                }
            }
            if (data.containsKey("tags")) {
                Map<String, Object> tags = (Map<String, Object>) data.get("tags");
                endpoint = (String) tags.get("url");
                metricName = (String) tags.get("metric_name");
                providerMode = (String) tags.get("provider_mode");
                Object status = tags.get("status");
                if (status != null) {
                    statusCode = Integer.parseInt(status.toString());
                }
                Object successTag = tags.get("success");
                if (successTag != null) {
                    success = Boolean.parseBoolean(successTag.toString());
                }
                Object errorTag = tags.get("error");
                if (errorTag != null) {
                    error = errorTag.toString();
                }
                Object ttftTag = tags.get("ttft_ms");
                if (ttftTag instanceof Number) {
                    ttftMs = ((Number) ttftTag).doubleValue();
                } else if (ttftTag != null) {
                    try {
                        ttftMs = Double.parseDouble(ttftTag.toString());
                    } catch (NumberFormatException ignored) {
                        // leave ttftMs null
                    }
                }
                Object t0Tag = tags.get("t0_monotonic");
                if (t0Tag instanceof Number) {
                    t0Monotonic = ((Number) t0Tag).doubleValue();
                } else if (t0Tag != null) {
                    try {
                        t0Monotonic = Double.parseDouble(t0Tag.toString());
                    } catch (NumberFormatException ignored) {
                    }
                }
                Object t1Tag = tags.get("t1_monotonic");
                if (t1Tag instanceof Number) {
                    t1Monotonic = ((Number) t1Tag).doubleValue();
                } else if (t1Tag != null) {
                    try {
                        t1Monotonic = Double.parseDouble(t1Tag.toString());
                    } catch (NumberFormatException ignored) {
                    }
                }
                qualifyingEventType = (String) tags.get("qualifying_event_type");
                Object vuTag = tags.get("vu");
                if (vuTag != null) {
                    try {
                        vu = Integer.parseInt(vuTag.toString());
                    } catch (NumberFormatException ignored) {
                    }
                }
                Object concurrencyTag = tags.get("concurrency");
                if (concurrencyTag != null) {
                    try {
                        concurrency = Integer.parseInt(concurrencyTag.toString());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            Object value = data.get("value");
            if (value instanceof Number) {
                // k6 http_req_duration is reported in milliseconds as a double.
                latencyMs = ((Number) value).doubleValue();
            }
        }

        return new BenchmarkRawSample(
                timestamp,
                scenario,
                runSetId,
                runId,
                sampleIndex,
                metricName != null ? metricName : metric,
                endpoint,
                latencyMs,
                ttftMs,
                statusCode,
                success,
                error,
                vu,
                concurrency,
                providerMode,
                configHash,
                environmentHash,
                k6SampleReference,
                executionMetadata,
                runRelativeTime,
                t0Monotonic,
                t1Monotonic,
                qualifyingEventType
        );
    }

    private static Double parseIsoToEpochMs(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return (double) Instant.parse(value).toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }
}
