package com.intellidesk.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Per-sample benchmark raw writer.
 *
 * <p>Writes samples as newline-delimited JSON to a file inside the run-set directory.
 * Each scenario/run may produce one or more raw files; the default naming convention
 * is {@code raw-<scenario>-<runId>.jsonl}.
 */
public class BenchmarkRawWriter {

    private static final ObjectMapper OM = new ObjectMapper();

    private final Path runSetDir;
    private final String scenario;
    private final String mode;
    private final String runId;
    private final Path rawFile;
    private final String configHash;
    private final String environmentHash;
    private final String runStartTime;
    private int nextSampleIndex = 0;

    public BenchmarkRawWriter(Path runSetDir, String scenario, String mode, String runId) {
        this(runSetDir, scenario, mode, runId, null, null, null);
    }

    public BenchmarkRawWriter(Path runSetDir, String scenario, String mode, String runId,
                              String configHash, String environmentHash) {
        this(runSetDir, scenario, mode, runId, configHash, environmentHash, null);
    }

    public BenchmarkRawWriter(Path runSetDir, String scenario, String mode, String runId,
                              String configHash, String environmentHash, String runStartTime) {
        this.runSetDir = runSetDir;
        this.scenario = scenario;
        this.mode = mode;
        this.runId = runId;
        this.rawFile = runSetDir.resolve("raw-" + scenario + "-" + mode + "-" + runId + ".jsonl");
        this.configHash = configHash;
        this.environmentHash = environmentHash;
        this.runStartTime = runStartTime;
    }

    /**
     * Writes a single sample to the raw file.
     */
    public synchronized void write(BenchmarkRawSample sample) throws IOException {
        BenchmarkRunSetManager.assertWritable(runSetDir);
        sample.appendTo(rawFile);
    }

    /**
     * Converts a k6 JSON record and writes it to the raw file.
     */
    @SuppressWarnings("unchecked")
    public void writeK6Record(Object k6Record) throws IOException {
        if (!(k6Record instanceof Map)) {
            throw new IllegalArgumentException("k6 record must be a JSON object");
        }
        Map<String, Object> record = OM.readValue(OM.writeValueAsString(k6Record), Map.class);
        BenchmarkRawSample sample = BenchmarkRawSample.fromK6Record(record, scenario,
                runSetDir.getFileName().toString(), runId, nextSampleIndex++, configHash, environmentHash, runStartTime);
        write(sample);
    }

    /**
     * Writes an arbitrary JSON object as one raw line.
     *
     * <p>Used by component-level harnesses (e.g. retrieval) that need a
     * scenario-specific raw schema distinct from the k6-derived HTTP schema.
     */
    public synchronized void writeRawLine(Map<String, Object> sample) throws IOException {
        BenchmarkRunSetManager.assertWritable(runSetDir);
        byte[] line = OM.writeValueAsBytes(sample);
        Files.write(rawFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.write(rawFile, new byte[]{'\n'}, StandardOpenOption.APPEND);
    }

    /**
     * Returns the raw file path.
     */
    public Path getRawFile() {
        return rawFile;
    }

    /**
     * Returns the number of samples currently written.
     */
    public long countSamples() throws IOException {
        if (!Files.exists(rawFile)) {
            return 0L;
        }
        return Files.lines(rawFile).filter(line -> !line.isBlank()).count();
    }
}
