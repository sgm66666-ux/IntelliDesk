package com.intellidesk.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intellidesk.document.DocumentChunk;
import com.intellidesk.document.DocumentChunkMapper;
import com.intellidesk.document.DocumentMapper;
import com.intellidesk.document.KnowledgeDocument;
import com.intellidesk.document.model.DocumentStatus;
import com.intellidesk.retrieval.task.DocumentRetrievalTask;
import com.intellidesk.retrieval.task.DocumentRetrievalTaskMapper;
import com.intellidesk.retrieval.task.RetrievalTaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * PostgreSQL authoritative hydration.
 * ES _source is not trusted as final content/permission authority.
 * Stale/orphan ES candidates are filtered out.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalHydrator {

    private final DocumentChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final DocumentRetrievalTaskMapper taskMapper;

    /**
     * Batch hydrate candidates from PostgreSQL with authority check.
     * Filters out stale/orphan candidates that don't pass READY + scope + generation checks.
     * Preserves candidate ordering.
     */
    public List<RetrievalResult> hydrate(List<RetrievalResult> candidates, RetrievalScope scope) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        Set<Long> chunkIds = candidates.stream()
                .map(RetrievalResult::getChunkId)
                .collect(Collectors.toSet());

        // 1. Batch load chunks
        List<DocumentChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                        .in(DocumentChunk::getId, chunkIds));

        if (chunks.isEmpty()) {
            return List.of();
        }

        Set<Long> documentIds = chunks.stream()
                .map(DocumentChunk::getDocumentId)
                .collect(Collectors.toSet());

        // 2. Batch load documents with scope + COMPLETED
        List<KnowledgeDocument> documents = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .in(KnowledgeDocument::getId, documentIds)
                        .eq(KnowledgeDocument::getStatus, DocumentStatus.COMPLETED.getValue())
                        .in(KnowledgeDocument::getKnowledgeBaseId, scope.knowledgeBaseIds()));

        Set<Long> validDocIds = documents.stream()
                .map(KnowledgeDocument::getId)
                .collect(Collectors.toSet());

        // 3. Batch load retrieval tasks for READY + generation match
        List<DocumentRetrievalTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<DocumentRetrievalTask>()
                        .in(DocumentRetrievalTask::getDocumentId, validDocIds)
                        .eq(DocumentRetrievalTask::getStatus, RetrievalTaskStatus.READY.getValue()));

        Map<Long, Integer> docGeneration = tasks.stream()
                .collect(Collectors.toMap(
                        DocumentRetrievalTask::getDocumentId,
                        DocumentRetrievalTask::getGeneration,
                        (a, b) -> a));

        // 4. Build chunk map: chunkId -> chunk
        Map<Long, DocumentChunk> chunkMap = chunks.stream()
                .collect(Collectors.toMap(DocumentChunk::getId, c -> c));

        // 5. Hydrate: validate each candidate
        Map<Long, RetrievalResult> resultMap = new HashMap<>();
        for (RetrievalResult candidate : candidates) {
            DocumentChunk chunk = chunkMap.get(candidate.getChunkId());
            if (chunk == null) continue;

            Integer gen = docGeneration.get(chunk.getDocumentId());
            if (gen == null) continue;

            // Check embedding generation matches task generation
            if (chunk.getEmbeddingGeneration() != null
                    && !chunk.getEmbeddingGeneration().equals(gen)) continue;

            // Apply optional document filter
            if (scope.hasDocumentFilter() && !scope.documentIds().contains(chunk.getDocumentId())) continue;

            RetrievalResult hydrated = new RetrievalResult(
                    chunk.getId(),
                    chunk.getDocumentId(),
                    chunk.getKnowledgeBaseId(),
                    chunk.getContent(),
                    candidate.getScore(),
                    candidate.getScoreType(),
                    chunk.getChunkIndex(),
                    chunk.getSectionPath() != null ? chunk.getSectionPath() : "",
                    candidate.getRetrievalSource(),
                    candidate.getMatchedSources(),
                    candidate.getSourceScores()
            );
            resultMap.put(candidate.getChunkId(), hydrated);
        }

        // 6. Preserve order, filter out invalid
        List<RetrievalResult> hydrated = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (RetrievalResult candidate : candidates) {
            RetrievalResult r = resultMap.get(candidate.getChunkId());
            if (r != null && seen.add(r.getChunkId())) {
                hydrated.add(r);
            }
        }

        log.debug("Hydration: {} candidates -> {} valid results", candidates.size(), hydrated.size());
        return hydrated;
    }
}