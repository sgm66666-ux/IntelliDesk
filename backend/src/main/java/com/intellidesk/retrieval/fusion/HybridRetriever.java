package com.intellidesk.retrieval.fusion;

import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.retrieval.*;
import com.intellidesk.retrieval.keyword.KeywordResult;
import com.intellidesk.retrieval.keyword.KeywordRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HybridRetriever composes VectorRetriever + KeywordRetriever + RRF only.
 * Does not know about Reranker, API keys, or HTTP DTOs.
 * <p>
 * Fail-closed contract: a failure from one source does not silently become
 * a successful hybrid response. The entire hybrid call fails with a sanitized
 * 503 error.
 */
@Slf4j
@Component
@Profile("!test")
public class HybridRetriever implements Retriever {

    private final VectorRetriever vectorRetriever;
    private final KeywordRetriever keywordRetriever;
    private final ReciprocalRankFusion rrf;

    public HybridRetriever(VectorRetriever vectorRetriever, KeywordRetriever keywordRetriever) {
        this.vectorRetriever = vectorRetriever;
        this.keywordRetriever = keywordRetriever;
        this.rrf = new ReciprocalRankFusion(60);
    }

    @Override
    public RetrievalSource source() {
        return RetrievalSource.HYBRID;
    }

    @Override
    public List<RetrievalResult> retrieve(RetrievalQuery query, RetrievalScope scope, int topK) {
        return retrieve(query, scope, topK, topK);
    }

    /**
     * Retrieve from both sources and fuse with RRF.
     * Fail-closed: if either source fails, the entire hybrid call fails.
     *
     * @param candidateTopN number of candidates per source
     * @param topK          final result count
     */
    public List<RetrievalResult> retrieve(RetrievalQuery query, RetrievalScope scope, int candidateTopN, int topK) {
        Map<RetrievalSource, List<RetrievalResult>> sourceResults = new LinkedHashMap<>();

        // Vector retrieval (fail-closed)
        List<RetrievalResult> vectorResults;
        try {
            vectorResults = vectorRetriever.retrieve(
                    query.query(), scope.workspaceId(),
                    scope.knowledgeBaseIds(), scope.documentIds(),
                    candidateTopN, candidateTopN);
        } catch (Exception e) {
            log.error("Vector retrieval failed for query: {}", query.query().substring(0, Math.min(50, query.query().length())), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Retrieval service temporarily unavailable");
        }
        sourceResults.put(RetrievalSource.VECTOR, vectorResults);

        // Keyword retrieval (fail-closed)
        List<KeywordResult> keywordResults;
        try {
            keywordResults = keywordRetriever.retrieve(
                    query.query(), scope.workspaceId(), scope.knowledgeBaseIds(),
                    scope.documentIds(), candidateTopN);
        } catch (Exception e) {
            log.error("Keyword retrieval failed for query: {}", query.query().substring(0, Math.min(50, query.query().length())), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Retrieval service temporarily unavailable");
        }

        // Convert KeywordResult to RetrievalResult (chunkId + BM25 score only)
        List<RetrievalResult> keywordAsResults = keywordResults.stream()
                .map(kr -> {
                    RetrievalResult r = new RetrievalResult(
                            kr.getChunkId(), null, null, null,
                            kr.getBm25Score().floatValue(), ScoreType.BM25,
                            0, null);
                    r.setRetrievalSource(RetrievalSource.KEYWORD);
                    return r;
                })
                .toList();
        sourceResults.put(RetrievalSource.KEYWORD, keywordAsResults);

        // RRF fusion
        List<RetrievalResult> fused = rrf.fuse(sourceResults, topK);
        log.debug("HybridRetriever: {} vector + {} keyword -> {} fused",
                vectorResults.size(), keywordResults.size(), fused.size());
        return fused;
    }
}