package com.intellidesk.retrieval.rerank;

import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.RetrievalSource;
import com.intellidesk.retrieval.ScoreType;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Provider-neutral rerank service.
 * Applies reranking to RRF candidates and returns final topK.
 */
@Slf4j
public class RerankService {

    private final RerankClient rerankClient;

    public RerankService(RerankClient rerankClient) {
        this.rerankClient = rerankClient;
    }

    /**
     * Rerank candidates and return final topK.
     * Preserves original RRF scores as diagnostics.
     */
    public List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates, int finalTopK) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        // Prepare documents for rerank
        List<String> documents = candidates.stream()
                .map(r -> r.getContent() != null ? r.getContent() : "")
                .collect(Collectors.toList());

        // Call rerank API
        List<RerankResponse.RerankResult> rerankResults;
        try {
            rerankResults = rerankClient.rerank(query, documents, finalTopK);
        } catch (Exception e) {
            log.error("Rerank call failed", e);
            throw new RuntimeException("Rerank service unavailable", e);
        }

        // Map back to RetrievalResult with rerank scores
        Map<Integer, RetrievalResult> indexMap = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            indexMap.put(i, candidates.get(i));
        }

        List<RetrievalResult> reranked = new ArrayList<>();
        for (RerankResponse.RerankResult rr : rerankResults) {
            RetrievalResult original = indexMap.get(rr.getIndex());
            if (original == null) {
                log.warn("Rerank returned index {} not in candidates", rr.getIndex());
                continue;
            }

            RetrievalResult rerankedResult = new RetrievalResult(
                    original.getChunkId(),
                    original.getDocumentId(),
                    original.getKnowledgeBaseId(),
                    original.getContent(),
                    (float) rr.getRelevanceScore(),
                    ScoreType.RERANKED,
                    original.getChunkIndex(),
                    original.getSectionPath(),
                    RetrievalSource.RERANKED,
                    original.getMatchedSources(),
                    original.getSourceScores()
            );
            reranked.add(rerankedResult);
        }

        log.debug("Rerank: {} candidates -> {} results", candidates.size(), reranked.size());
        return reranked;
    }
}