package com.intellidesk.retrieval;

import com.intellidesk.retrieval.fusion.HybridRetriever;
import com.intellidesk.retrieval.rerank.RerankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Retrieval orchestration service.
 * request -> authorized RetrievalScope -> mode selection -> retrieve -> optional rerank -> hydrate -> result
 */
@Slf4j
@Service
public class RetrievalService {

    private final VectorRetriever vectorRetriever;
    private final com.intellidesk.retrieval.keyword.KeywordRetriever keywordRetriever;
    private final HybridRetriever hybridRetriever;
    private final RetrievalHydrator hydrator;
    private final RerankService rerankService; // may be null if rerank disabled

    public RetrievalService(VectorRetriever vectorRetriever,
                            com.intellidesk.retrieval.keyword.KeywordRetriever keywordRetriever,
                            HybridRetriever hybridRetriever,
                            RetrievalHydrator hydrator,
                            @Autowired(required = false) RerankService rerankService) {
        this.vectorRetriever = vectorRetriever;
        this.keywordRetriever = keywordRetriever;
        this.hybridRetriever = hybridRetriever;
        this.hydrator = hydrator;
        this.rerankService = rerankService;
    }

    /**
     * Execute retrieval with the given mode and parameters.
     */
    public List<RetrievalResult> search(RetrievalQuery query, RetrievalMode mode,
                                        int candidateTopK, int topK, boolean rerank) {
        List<RetrievalResult> candidates;

        switch (mode) {
            case VECTOR -> candidates = vectorRetriever.retrieve(
                    query.query(), query.scope().workspaceId(),
                    query.scope().knowledgeBaseIds(), query.scope().documentIds(),
                    candidateTopK, topK);
            case KEYWORD -> {
                List<com.intellidesk.retrieval.keyword.KeywordResult> kwResults = keywordRetriever.retrieve(
                        query.query(), query.scope().workspaceId(),
                        query.scope().knowledgeBaseIds(), query.scope().documentIds(), candidateTopK);
                candidates = kwResults.stream()
                        .map(kr -> {
                            RetrievalResult r = new RetrievalResult(
                                    kr.getChunkId(), null, null, null,
                                    kr.getBm25Score().floatValue(), ScoreType.BM25, 0, null);
                            r.setRetrievalSource(RetrievalSource.KEYWORD);
                            return r;
                        })
                        .toList();
            }
            case HYBRID -> candidates = hybridRetriever.retrieve(query, query.scope(), candidateTopK, topK);
            default -> throw new IllegalArgumentException("Unsupported retrieval mode: " + mode);
        }

        // Hydrate candidates from PostgreSQL (authority check)
        List<RetrievalResult> hydrated = hydrator.hydrate(candidates, query.scope());

        // Optional rerank
        if (rerank && rerankService != null && !hydrated.isEmpty()) {
            try {
                List<RetrievalResult> reranked = rerankService.rerank(query.query(), hydrated, topK);
                // Hydrate again after rerank (content comes from rerank service, but we need PG authority)
                return hydrator.hydrate(reranked, query.scope());
            } catch (Exception e) {
                log.error("Rerank failed, returning 503", e);
                throw new RuntimeException("Rerank service unavailable", e);
            }
        }

        // Apply topK
        return hydrated.stream().limit(topK).toList();
    }
}