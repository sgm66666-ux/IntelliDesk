package com.intellidesk.retrieval;

import com.intellidesk.embedding.EmbeddingException;
import com.intellidesk.embedding.EmbeddingService;
import com.intellidesk.infrastructure.config.RetrievalProperties;
import com.intellidesk.retrieval.repository.ChunkVectorRow;
import com.intellidesk.retrieval.repository.PgVectorChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class VectorRetriever {

    private static final Logger log = LoggerFactory.getLogger(VectorRetriever.class);

    private final EmbeddingService embeddingService;
    private final PgVectorChunkRepository pgVectorChunkRepository;
    private final RetrievalProperties properties;

    public VectorRetriever(EmbeddingService embeddingService,
                           PgVectorChunkRepository pgVectorChunkRepository,
                           RetrievalProperties properties) {
        this.embeddingService = embeddingService;
        this.pgVectorChunkRepository = pgVectorChunkRepository;
        this.properties = properties;
    }

    /**
     * Retrieve chunks by vector similarity within the given scope.
     * Generation correctness is enforced per-document in SQL
     * (c.embedding_generation = rt.generation), not via a query-level parameter.
     *
     * @param query            user query text
     * @param workspaceId      workspace scope
     * @param knowledgeBaseIds KB scope (must not be empty)
     * @param documentIds      optional document scope filter
     * @param candidateTopK    number of candidates to fetch from pgvector
     * @param topK             final result count after truncation
     * @return ordered list of retrieval results (highest score first)
     */
    public List<RetrievalResult> retrieve(
            String query,
            Long workspaceId,
            List<Long> knowledgeBaseIds,
            List<Long> documentIds,
            int candidateTopK,
            int topK) {

        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be empty");
        }

        // 1. Embed query
        float[] queryVector;
        try {
            queryVector = embeddingService.embedQuery(query);
        } catch (EmbeddingException e) {
            log.error("Failed to embed query", e);
            throw e;
        }

        // 2. Validate dimension
        if (queryVector.length != 1536) {
            throw new IllegalStateException(
                    String.format("Query vector dimension mismatch: expected 1536, got %d", queryVector.length));
        }

        // 3. Vector search with scope
        List<ChunkVectorRow> rows = pgVectorChunkRepository.search(
                queryVector, workspaceId, knowledgeBaseIds, documentIds, candidateTopK);

        // 4. Map to results with deterministic ordering, truncate to topK
        List<RetrievalResult> results = rows.stream()
                .map(row -> new RetrievalResult(
                        row.getChunkId(),
                        row.getDocumentId(),
                        row.getKnowledgeBaseId(),
                        row.getContent(),
                        row.getScore(),
                        ScoreType.COSINE_SIMILARITY,
                        row.getChunkIndex(),
                        row.getSectionPath()
                ))
                .sorted(Comparator.comparing(RetrievalResult::getScore).reversed()
                        .thenComparing(RetrievalResult::getChunkId))
                .limit(topK)
                .toList();

        log.debug("VectorRetriever returned {} results for query: {}", results.size(), query.substring(0, Math.min(50, query.length())));
        return results;
    }
}