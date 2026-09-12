package com.intellidesk.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 8 Wave 1 — recompute path (raw -> derived metrics, NO retrieval re-run).
 *
 * Reads only the persisted raw run files (docs/evaluation/raw/eval-*.json) plus the frozen
 * evaluation dataset, computes Hit@K / Recall@K / MRR (chunk + document level, K in
 * {1,3,5,10}) per mode, verifies determinism between run_index 1 and 2 for the deterministic
 * modes, and writes the aggregate to docs/evaluation/derived/metrics_summary.json.
 *
 * This is the independent-reproducibility path: an independent party can run this script and
 * reproduce exactly the numbers reported in docs/evaluation/report.md without touching retrieval.
 *
 * Not auto-discovered by surefire (name does not match *Test); run on demand:
 *   mvn -Dtest=EvalRecomputeScript test
 */
public class EvalRecomputeScript {

    private static final ObjectMapper OM = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void recomputeFromRaw() throws Exception {
        String root = EvalManifestGenerator.resolveProjectRoot();
        String rawDir = root + "/docs/evaluation/raw";
        String datasetPath = root + "/docs/evaluation/dataset/evaluation_dataset.json";
        String summaryPath = root + "/docs/evaluation/derived/metrics_summary.json";
        recompute(Path.of(rawDir), Path.of(summaryPath), Path.of(datasetPath));
    }

    /**
     * Independent raw-only recompute from the new immutable run-set namespace.
     * Verifies that metrics can be reproduced from persisted raw without retrieval.
     */
    @Test
    void recomputeFromNewRunSet() throws Exception {
        String root = EvalManifestGenerator.resolveProjectRoot();
        Path rawDir = Path.of(root, "docs", "evaluation", "raw", "real-quality",
                "2ae529f6aa1ba508", "implementation-compensation-001");
        Path datasetPath = Path.of(root, "docs", "evaluation", "dataset", "evaluation_dataset.json");
        Path summaryPath = rawDir.resolve("metrics_summary_independent_recompute.json");
        recompute(rawDir, summaryPath, datasetPath);
    }

    /**
     * Recompute metrics from any raw directory (implementation-side legacy or new immutable run-set).
     * Writes the metrics_summary.json next to the raw files.
     */
    public static void recompute(Path rawDir, Path summaryOut, Path datasetPath) throws Exception {
        // 1. Load frozen dataset -> truth by question id
        Map<String, EvalDatasetValidator.Question> truth = loadTruth(datasetPath.toString());
        System.out.println("frozen dataset questions loaded: " + truth.size());

        // 2. Group raw files by (mode, run_index)
        Map<String, Map<Integer, List<JsonNode>>> byModeRun = new LinkedHashMap<>();
        File[] raws = rawDir.toFile().listFiles((d, name) -> name.startsWith("eval-") && name.endsWith(".json"));
        assertTrue(raws != null && raws.length >= 7, "expected >=7 raw run files in " + rawDir + ", got " + (raws == null ? 0 : raws.length));
        java.util.Arrays.sort(raws, java.util.Comparator.comparing(File::getName));
        for (File f : raws) {
            JsonNode run = OM.readTree(f);
            String mode = run.get("mode").asText();
            int runIndex = run.get("run_index").asInt();
            byModeRun.computeIfAbsent(mode, k -> new LinkedHashMap<>())
                    .computeIfAbsent(runIndex, k -> new ArrayList<>())
                    .add(run);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> modes = new LinkedHashMap<>();

        // 3. Per (mode, run): compute aggregate metrics at chunk + doc level for each K
        for (Map.Entry<String, Map<Integer, List<JsonNode>>> e : byModeRun.entrySet()) {
            String mode = e.getKey();
            Map<String, Object> modeBlock = new LinkedHashMap<>();
            Map<String, Object> runs = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<JsonNode>> re : e.getValue().entrySet()) {
                int runIndex = re.getKey();
                for (JsonNode f : re.getValue()) {
                    Map<String, Object> runBlock = computeRunMetrics(f, truth);
                    runs.put(String.valueOf(runIndex), runBlock);
                }
            }
            modeBlock.put("runs", runs);
            modeBlock.put("corpus_hash", firstFieldAmongMode(e.getValue(), "corpus_hash"));
            modeBlock.put("dataset_hash", firstFieldAmongMode(e.getValue(), "dataset_hash"));
            modeBlock.put("config_hash", firstFieldAmongMode(e.getValue(), "config_hash"));
            modes.put(mode, modeBlock);
        }

        // 4. Determinism check: run1 vs run2 for deterministic modes (VECTOR/KEYWORD/HYBRID)
        Map<String, Object> determinism = new LinkedHashMap<>();
        for (String mode : List.of("VECTOR", "KEYWORD", "HYBRID")) {
            Map<Integer, List<JsonNode>> byRun = byModeRun.get(mode);
            boolean identical = false;
            if (byRun != null && byRun.containsKey(1) && byRun.containsKey(2)) {
                identical = metricsEqual(modes, mode, 1, 2);
            }
            determinism.put(mode, identical ? "IDENTICAL" : "DIFFERENT");
            System.out.println("recompute determinism[" + mode + "] run1==run2 metrics: "
                    + (identical ? "IDENTICAL" : "DIFFERENT"));
        }

        summary.put("derived_from", rawDir.toString().replace('\\', '/') + "/eval-*.json + " + datasetPath.toString().replace('\\', '/'));
        summary.put("recompute_path", "reads raw only; no retrieval re-run; deterministic");
        summary.put("modes", modes);
        summary.put("determinism", determinism);
        summary.put("eligible_quality_questions", 60);

        Files.createDirectories(summaryOut.getParent());
        Files.write(summaryOut, OM.writeValueAsBytes(summary));
        System.out.println("wrote " + summaryOut);

        assertTrue(determinism.get("VECTOR").equals("IDENTICAL"), "VECTOR determinism failed");
        assertTrue(determinism.get("KEYWORD").equals("IDENTICAL"), "KEYWORD determinism failed");
        assertTrue(determinism.get("HYBRID").equals("IDENTICAL"), "HYBRID determinism failed");
    }

    private static Map<String, Object> computeRunMetrics(JsonNode run, Map<String, EvalDatasetValidator.Question> truth) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("run_id", run.get("run_id").asText());
        String mode = run.get("mode").asText();
        int runIndex = run.get("run_index").asInt();
        List<Map<String, Object>> levels = new ArrayList<>();
        for (String level : List.of("chunk", "doc")) {
            List<Map<String, Object>> kRows = new ArrayList<>();
            for (int k : EvalMetricsCalculator.REPORTED_KS) {
                Map<String, Object> agg = aggregateForLevelAtK(run, truth, level, k);
                kRows.add(agg);
            }
            levels.add(Map.of("level", level, "k", kRows));
        }
        block.put("levels", levels);
        block.put("question_count", run.get("questions").size());
        return block;
    }

    private static Map<String, Object> aggregateForLevelAtK(JsonNode run, Map<String, EvalDatasetValidator.Question> truth,
                                                              String level, int k) {
        List<EvalMetricsCalculator.MetricsForQuery> perQuery = new ArrayList<>();
        for (JsonNode q : run.get("questions")) {
            String qid = q.get("question_id").asText();
            EvalDatasetValidator.Question t = truth.get(qid);
            if (t == null) {
                continue;
            }
            List<EvalMetricsCalculator.Item> chunkItems = new ArrayList<>();
            for (JsonNode r : q.get("ranked")) {
                JsonNode lc = r.get("logical_chunk_id");
                if (lc == null || lc.isNull()) {
                    continue;
                }
                boolean rel = r.has("relevant") && r.get("relevant").asBoolean();
                chunkItems.add(new EvalMetricsCalculator.Item(
                        "chunk", lc.asText(), (float) r.get("score").asDouble(), rel));
            }
            EvalMetricsCalculator.Truth tSet = new EvalMetricsCalculator.Truth(
                    new LinkedHashSet<>(t.relevantChunks()),
                    new LinkedHashSet<>(t.relevantDocuments()));
            perQuery.add(EvalMetricsCalculator.metricsForQuery(chunkItems, tSet, k));
        }
        EvalMetricsCalculator.Aggregate agg = EvalMetricsCalculator.aggregate(perQuery, level, k);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("k", k);
        row.put("hitK", round5(agg.hitK()));
        row.put("recallK", round5(agg.recallK()));
        row.put("mrr", round5(agg.mrr()));
        row.put("eligible", agg.eligibleCount());
        row.put("hit_count", agg.hitCount());
        return row;
    }

    private static boolean metricsEqual(Map<String, Object> modes, String mode, int r1, int r2) {
        Map<String, Object> m = castMap(modes.get(mode));
        Map<String, Object> runs = castMap(m.get("runs"));
        Map<String, Object> b1 = castMap(runs.get(String.valueOf(r1)));
        Map<String, Object> b2 = castMap(runs.get(String.valueOf(r2)));
        if (b1 == null || b2 == null) {
            return false;
        }
        return b1.get("levels").toString().equals(b2.get("levels").toString())
                && b1.get("question_count").equals(b2.get("question_count"));
    }

    private static String firstFieldAmongMode(Map<Integer, List<JsonNode>> byRun, String field) {
        for (List<JsonNode> files : byRun.values()) {
            for (JsonNode f : files) {
                String v = f.path(field).asText();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        return null;
    }

    private static Map<String, EvalDatasetValidator.Question> loadTruth(String datasetPath) throws Exception {
        JsonNode root = OM.readTree(new File(datasetPath));
        List<JsonNode> nodes = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(nodes::add);
        } else if (root.has("questions")) {
            root.get("questions").forEach(nodes::add);
        }
        List<Map<String, Object>> raw = OM.convertValue(nodes,
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        List<EvalDatasetValidator.Question> questions = EvalDatasetValidator.validate(raw).questions();
        Map<String, EvalDatasetValidator.Question> byId = new LinkedHashMap<>();
        for (EvalDatasetValidator.Question q : questions) {
            byId.put(q.id(), q);
        }
        return byId;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static double round5(double v) {
        return Math.round(v * 100_000.0) / 100_000.0;
    }
}