package com.intellidesk.retrieval.fusion;

import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.RetrievalSource;
import com.intellidesk.retrieval.ScoreType;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * IntelliDesk-owned Reciprocal Rank Fusion implementation.
 * Formula: fusedScore(chunk) = sum over sources (1 / (k + rank_in_source))
 * 1-based rank, default k=60.
 */
@Slf4j
public class ReciprocalRankFusion {

    private final int k;

    public ReciprocalRankFusion(int k) {
        if (k < 1 || k > 1000) {
            throw new IllegalArgumentException("k must be between 1 and 1000, got: " + k);
        }
        this.k = k;
    }

    public ReciprocalRankFusion() {
        this(60);
    }

    /**
     * Fuse multiple source result lists using RRF.
     * Each source list is deterministically sorted by raw score desc, chunkId asc.
     * Duplicate chunkId within same source is removed before rank assignment.
     * Final order: RRF score desc, matched source count desc, best source rank asc, chunkId asc.
     *
     * @param sourceResults ordered map of source -> results (already sorted within each source)
     * @param topK          final result count limit
     * @return fused results
     */
    public List<RetrievalResult> fuse(Map<RetrievalSource, List<RetrievalResult>> sourceResults, int topK) {
        if (sourceResults.isEmpty()) {
            return List.of();
        }

        // Per-source: deduplicate by chunkId (first occurrence wins, preserves score order)
        Map<RetrievalSource, List<RetrievalResult>> deduped = new LinkedHashMap<>();
        for (var entry : sourceResults.entrySet()) {
            List<RetrievalResult> sourceList = entry.getValue();
            Map<Long, RetrievalResult> seen = new LinkedHashMap<>();
            for (RetrievalResult r : sourceList) {
                seen.putIfAbsent(r.getChunkId(), r);
            }
            deduped.put(entry.getKey(), new ArrayList<>(seen.values()));
        }

        // Build per-chunk aggregation
        Map<Long, FusionEntry> fusionMap = new LinkedHashMap<>();

        for (var entry : deduped.entrySet()) {
            RetrievalSource source = entry.getKey();
            List<RetrievalResult> results = entry.getValue();
            for (int i = 0; i < results.size(); i++) {
                RetrievalResult r = results.get(i);
                int rank = i + 1; // 1-based
                double rrfScore = 1.0 / (k + rank);

                fusionMap.compute(r.getChunkId(), (chunkId, existing) -> {
                    FusionEntry fe = existing != null ? existing : new FusionEntry(r);
                    fe.addSource(source, r.getScore(), rrfScore, rank);
                    return fe;
                });
            }
        }

        // Build final results
        List<RetrievalResult> fused = fusionMap.values().stream()
                .map(fe -> fe.toResult())
                .sorted(Comparator
                        .comparing(RetrievalResult::getScore).reversed()
                        .thenComparingInt((RetrievalResult r) -> r.getMatchedSources().size()).reversed()
                        .thenComparingInt((RetrievalResult r) -> getBestSourceRank(r)).reversed()
                        .thenComparing(RetrievalResult::getChunkId))
                .limit(topK)
                .collect(Collectors.toList());

        log.debug("RRF(k={}): {} sources -> {} fused results", k, sourceResults.size(), fused.size());
        return fused;
    }

    /**
     * Get the best (lowest) rank among all sources for tie-breaking.
     * Returns negated value so that .reversed() puts lower ranks first.
     */
    private int getBestSourceRank(RetrievalResult r) {
        if (r.getSourceScores() == null || r.getSourceScores().isEmpty()) {
            return Integer.MIN_VALUE;
        }
        int bestRank = r.getSourceScores().entrySet().stream()
                .filter(e -> e.getKey().endsWith("_rank"))
                .mapToInt(e -> e.getValue().intValue())
                .min()
                .orElse(Integer.MAX_VALUE);
        return -bestRank;
    }

    private static class FusionEntry {
        final Long chunkId;
        final Long documentId;
        final Long knowledgeBaseId;
        final String content;
        final int chunkIndex;
        final String sectionPath;
        final List<RetrievalSource> matchedSources;
        final Map<String, Float> sourceScores;
        double totalRrf;

        FusionEntry(RetrievalResult r) {
            this.chunkId = r.getChunkId();
            this.documentId = r.getDocumentId();
            this.knowledgeBaseId = r.getKnowledgeBaseId();
            this.content = r.getContent();
            this.chunkIndex = r.getChunkIndex();
            this.sectionPath = r.getSectionPath();
            this.matchedSources = new ArrayList<>();
            this.sourceScores = new LinkedHashMap<>();
            this.totalRrf = 0;
        }

        void addSource(RetrievalSource source, float rawScore, double rrfScore, int rank) {
            matchedSources.add(source);
            sourceScores.put(source.name().toLowerCase() + "_score", rawScore);
            sourceScores.put(source.name().toLowerCase() + "_rrf", (float) rrfScore);
            sourceScores.put(source.name().toLowerCase() + "_rank", (float) rank);
            totalRrf += rrfScore;
        }

        RetrievalResult toResult() {
            return new RetrievalResult(
                    chunkId, documentId, knowledgeBaseId, content,
                    (float) totalRrf, ScoreType.RRF, chunkIndex,
                    sectionPath, RetrievalSource.HYBRID,
                    matchedSources, sourceScores);
        }
    }
}