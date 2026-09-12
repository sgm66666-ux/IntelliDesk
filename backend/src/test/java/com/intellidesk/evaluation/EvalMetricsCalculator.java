package com.intellidesk.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 8 Wave 1 metric definitions (Hit@K / Recall@K / MRR), applied per eligible
 * quality question. Every computation is pure and deterministic on the supplied ranked
 * list, so metrics can ALWAYS be recomputed from persisted raw results.
 *
 * Eligible quality question: qualified_quality_question==true AND |relevant_chunks|>=1.
 * Questions with no relevant chunk are excluded from the eligible-mean base.
 *
 * Metric math (q = question):
 *   Ret_K(q)   = top-K retrieved logical chunks
 *   Rel(q)     = frozen relevant chunk set
 *   Hit@K(q)   = 1 if Ret_K(q) ∩ Rel(q) != empty else 0
 *   Recall@K(q)= |Ret_K(q) ∩ Rel(q)| / |Rel(q)|
 *   MRR(q)     = 1 / rank(first relevant); 0 if no relevant in full list
 *
 * Document-level: operate on the document rank list (dedup by first occurrence of
 * document in the chunk rank list) against the relevant document set.
 */
public final class EvalMetricsCalculator {

    /** One retrieved item from a single run: a scored logical reference. */
    public record Item(String level, String logicalId, double score, boolean relevant) {
    }

    /** Per-question releavance truth. */
    public record Truth(Set<String> relevantChunks, Set<String> relevantDocs) {
    }

    /** Metrics for one level (chunk or document) of one question. */
    public record LevelMetrics(boolean eligible, boolean hit, double recall, double mrr,
                               Integer firstRelevantRank, int retrievedRelevant) {
    }

    /** Aggregated metrics over a set of questions for a single level. */
    public record Aggregate(double hitK, double recallK, double mrr, int eligibleCount,
                            int hitCount) {
    }

    /** The ordered K values to report, frozen before any final run. */
    public static final List<Integer> REPORTED_KS = List.of(1, 3, 5, 10);

    private EvalMetricsCalculator() {
    }

    /**
     * Compute chunk- and document-level metrics for one question given its ranked items.
     *
     * @param rankedItems ranked retrieval list (must already be in final order)
     * @param truth       relevant chunk/document sets
     * @param k           report cut-off K
     */
    public static MetricsForQuery metricsForQuery(List<Item> rankedItems, Truth truth, int k) {
        // chunk-level
        LevelMetrics chunk = computeLevel(rankedItems, truth.relevantChunks(), k, "chunk");
        // document-level: dedup by (logicalId of doc) preserving first-occurrence order
        List<Item> docRanked = dedupeByDoc(rankedItems);
        LevelMetrics doc = computeLevel(docRanked, truth.relevantDocs(), k, "doc");
        return new MetricsForQuery(chunk, doc);
    }

    public record MetricsForQuery(LevelMetrics chunk, LevelMetrics doc) {
    }

    private static LevelMetrics computeLevel(List<Item> ranked, Set<String> relevant, int k, String level) {
        if (relevant == null || relevant.isEmpty()) {
            // no relevant at this level -> not an eligible query for this level's mean
            return new LevelMetrics(false, false, 0.0, 0.0, null, 0);
        }
        boolean eligible = true;
        int topK = Math.min(k, ranked.size());
        int retrievedRelevantInK = 0;
        int firstRelevantRank = 0;
        for (int i = 0; i < ranked.size(); i++) {
            Item it = ranked.get(i);
            if (!it.level().equals(level)) {
                continue;
            }
            boolean rel = relevant.contains(it.logicalId());
            if (i < topK && rel) {
                retrievedRelevantInK++;
            }
            if (rel && firstRelevantRank == 0) {
                firstRelevantRank = i + 1; // 1-based
            }
        }
        boolean hit = retrievedRelevantInK > 0;
        double recall = relevant.size() > 0 ? (double) retrievedRelevantInK / relevant.size() : 0.0;
        double mrr = firstRelevantRank > 0 ? 1.0 / firstRelevantRank : 0.0;
        return new LevelMetrics(eligible, hit, recall, mrr,
                firstRelevantRank == 0 ? null : firstRelevantRank, retrievedRelevantInK);
    }

    private static List<Item> dedupeByDoc(List<Item> ranked) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Item it : ranked) {
            // doc logical id is derived from chunk logical id before this stage
            // (responsible caller passes chunk-level items; here we key on logicalId of doc)
            String docKey = docKeyOf(it);
            if (seen.add(docKey)) {
                out.add(new Item("doc", docKey, it.score(), it.relevant()));
            }
        }
        return out;
    }

    private static String docKeyOf(Item it) {
        // chunk logical id format: "docLogicalId|chunkOrdinal" -> doc part is before '|'
        int bar = it.logicalId().indexOf('|');
        return bar >= 0 ? it.logicalId().substring(0, bar) : it.logicalId();
    }

    /**
     * Aggregate per-level metrics across questions (mean over eligible questions).
     * A question contributes to a level's mean if it has >=1 relevant at that level in the
     * TRUTH (independent of retrieval outcome). A question that retrieved no relevant at
     * that level still contributes 0 toward hit/recall/mrr — it is NOT dropped.
     */
    public static Aggregate aggregate(List<MetricsForQuery> perQuestion, String level, int k) {
        int eligible = 0;
        int hitCount = 0;
        double sumRecall = 0, sumMrr = 0;
        for (MetricsForQuery m : perQuestion) {
            LevelMetrics lm = level.equals("chunk") ? m.chunk() : m.doc();
            if (!lm.eligible()) {
                // no relevant in truth at this level (adversarial/no-answer) -> excluded from base
                continue;
            }
            eligible++;
            if (lm.hit()) {
                hitCount++;
            }
            sumRecall += lm.recall();
            sumMrr += lm.mrr();
        }
        double hitK = eligible > 0 ? (double) hitCount / eligible : 0.0;
        double recallK = eligible > 0 ? sumRecall / eligible : 0.0;
        double mrr = eligible > 0 ? sumMrr / eligible : 0.0;
        return new Aggregate(hitK, recallK, mrr, eligible, hitCount);
    }
}