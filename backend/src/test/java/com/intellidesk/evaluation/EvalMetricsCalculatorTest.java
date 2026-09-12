package com.intellidesk.evaluation;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the Phase 8 Wave 1 metric calculator: Hit@K, Recall@K, MRR at
 * chunk and document level, including single-relevant, multi-relevant, no-hit,
 * and zero-relevant-exclusion cases.
 */
class EvalMetricsCalculatorTest {

    private static EvalMetricsCalculator.Item chunk(String logicalChunkId, double score) {
        return new EvalMetricsCalculator.Item("chunk", logicalChunkId, score, true);
    }

    // doc-a has chunks a|0,a|1 ; doc-b has chunk b|0
    private static final List<EvalMetricsCalculator.Item> ranked = List.of(
            chunk("a|0", 0.9), chunk("b|0", 0.8), chunk("a|1", 0.7), chunk("c|0", 0.6));

    private static EvalMetricsCalculator.Truth truth(Set<String> chunks, Set<String> docs) {
        return new EvalMetricsCalculator.Truth(chunks, docs);
    }

    @Test
    void hitKAtTopSingleRelevant() {
        EvalMetricsCalculator.Truth t = truth(Set.of("a|0"), Set.of("a"));
        EvalMetricsCalculator.MetricsForQuery m = EvalMetricsCalculator.metricsForQuery(ranked, t, 1);
        assertTrue(m.chunk().hit());
        assertEquals(1, m.chunk().retrievedRelevant());
        assertEquals(1.0, m.chunk().mrr()); // first relevant at rank 1
        assertTrue(m.doc().hit());
        assertEquals(1, m.doc().retrievedRelevant());
        assertEquals(1.0, m.doc().mrr());
    }

    @Test
    void hitKSecondRank() {
        EvalMetricsCalculator.Truth t = truth(Set.of("b|0"), Set.of("b"));
        EvalMetricsCalculator.MetricsForQuery m = EvalMetricsCalculator.metricsForQuery(ranked, t, 3);
        assertTrue(m.chunk().hit());
        assertEquals(2, m.chunk().firstRelevantRank());
        assertEquals(0.5, m.chunk().mrr());
        assertTrue(m.doc().hit());
        assertEquals(2, m.doc().firstRelevantRank());
        assertEquals(0.5, m.doc().mrr());
    }

    @Test
    void recallMultiRelevant() {
        // relevant chunk set = {a|0, a|1, d|9}
        EvalMetricsCalculator.Truth t = truth(Set.of("a|0", "a|1", "d|9"), Set.of("a", "d"));
        EvalMetricsCalculator.MetricsForQuery m = EvalMetricsCalculator.metricsForQuery(ranked, t, 10);
        // retrieved relevant in top-10 = a|0 (rank1), a|1 (rank3) -> 2 of 3
        assertEquals(2.0 / 3.0, m.chunk().recall(), 1e-9);
        assertEquals(1.0, m.chunk().mrr()); // a|0 at rank1
        // document level: relevant docs {a, d}; in top-10 ranking, doc a appears (rank1), d not present -> retrieval of relevant docs = {a}
        // doc ranked: [a, b, c] (dedup first occurrence); relevant docs present in ranks = {a}
        assertEquals(1.0 / 2.0, m.doc().recall(), 1e-9);
        assertEquals(1.0, m.doc().mrr());
    }

    @Test
    void hitBehindKIsNoHitButMrrIsFullListRank() {
        // b|0 at rank2 ; with K=1 only rank1 counts -> no hit at K=1
        EvalMetricsCalculator.Truth t = truth(Set.of("b|0"), Set.of("b"));
        EvalMetricsCalculator.MetricsForQuery m = EvalMetricsCalculator.metricsForQuery(ranked, t, 1);
        assertFalse(m.chunk().hit());
        assertEquals(0, m.chunk().retrievedRelevant());
        // MRR is defined over the FULL ranked list (first relevant rank), not K-truncated
        assertEquals(2, m.chunk().firstRelevantRank());
        assertEquals(0.5, m.chunk().mrr());
        assertFalse(m.doc().hit());
        assertEquals(2, m.doc().firstRelevantRank());
        assertEquals(0.5, m.doc().mrr());
    }

    @Test
    void noRelevantGivesZeroMmrNoHit() {
        EvalMetricsCalculator.Truth t = truth(Set.of("zz|0"), Set.of("zz"));
        EvalMetricsCalculator.MetricsForQuery m = EvalMetricsCalculator.metricsForQuery(ranked, t, 10);
        assertFalse(m.chunk().hit());
        assertEquals(null, m.chunk().firstRelevantRank());
        assertEquals(0.0, m.chunk().mrr());
        assertEquals(0.0, m.chunk().recall());
        assertFalse(m.doc().hit());
        assertEquals(0.0, m.doc().mrr());
    }

    @Test
    void noHitRetrievedStillCountsTowardsMean() {
        // q1 hits a|0 at rank1 ; q2 has truth {zz|0} (eligible) but zz|0 is NOT retrieved
        EvalMetricsCalculator.Truth q1 = truth(Set.of("a|0"), Set.of("a"));
        EvalMetricsCalculator.Truth q2 = truth(Set.of("zz|0"), Set.of("zz"));
        EvalMetricsCalculator.MetricsForQuery m1 = EvalMetricsCalculator.metricsForQuery(ranked, q1, 3);
        EvalMetricsCalculator.MetricsForQuery m2 = EvalMetricsCalculator.metricsForQuery(ranked, q2, 3);
        // both are eligible (relevant set non-empty in truth); q2 contributes 0
        EvalMetricsCalculator.Aggregate agg = EvalMetricsCalculator.aggregate(List.of(m1, m2), "chunk", 3);
        assertEquals(2, agg.eligibleCount());
        assertEquals(0.5, agg.hitK(), 1e-9);   // (1 + 0) / 2
        assertEquals(0.5, agg.mrr(), 1e-9);    // (1 + 0) / 2
        assertEquals(1, agg.hitCount());
    }

    @Test
    void adversarialNoRelevantExcludedFromBase() {
        // quality q1 hits a|0 ; adversarial q2 has EMPTY truth -> excluded from base
        EvalMetricsCalculator.Truth q1 = truth(Set.of("a|0"), Set.of("a"));
        EvalMetricsCalculator.Truth q2 = new EvalMetricsCalculator.Truth(Set.of(), Set.of());
        EvalMetricsCalculator.MetricsForQuery m1 = EvalMetricsCalculator.metricsForQuery(ranked, q1, 3);
        EvalMetricsCalculator.MetricsForQuery m2 = EvalMetricsCalculator.metricsForQuery(ranked, q2, 3);
        assertFalse(m2.chunk().eligible());
        EvalMetricsCalculator.Aggregate agg = EvalMetricsCalculator.aggregate(List.of(m1, m2), "chunk", 3);
        assertEquals(1, agg.eligibleCount());
        assertEquals(1.0, agg.hitK(), 1e-9);
        assertEquals(1.0, agg.mrr(), 1e-9);
    }

    @Test
    void documentLevelDeduplicatesChunksOfSameDoc() {
        // K=1: only a|0 (doc a) retrieved -> doc hit
        EvalMetricsCalculator.Truth t = truth(Set.of("a|0", "a|1"), Set.of("a"));
        EvalMetricsCalculator.MetricsForQuery m1 = EvalMetricsCalculator.metricsForQuery(ranked, t, 1);
        assertTrue(m1.doc().hit());
        assertEquals(1.0, m1.doc().mrr());
        // but K=1 chunk-level also hits (a|0)
        assertTrue(m1.chunk().hit());
    }

    @Test
    void computeLevelIsDeterministic() {
        EvalMetricsCalculator.Truth t = truth(Set.of("a|0"), Set.of("a"));
        EvalMetricsCalculator.MetricsForQuery m1 = EvalMetricsCalculator.metricsForQuery(ranked, t, 5);
        EvalMetricsCalculator.MetricsForQuery m2 = EvalMetricsCalculator.metricsForQuery(ranked, t, 5);
        assertEquals(m1.chunk(), m2.chunk());
        assertEquals(m1.doc(), m2.doc());
    }
}