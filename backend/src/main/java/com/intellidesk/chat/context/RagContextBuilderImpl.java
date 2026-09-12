package com.intellidesk.chat.context;

import com.intellidesk.document.DocumentChunk;
import com.intellidesk.document.DocumentChunkMapper;
import com.intellidesk.document.DocumentMapper;
import com.intellidesk.document.KnowledgeDocument;
import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.ScoreType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of RagContextBuilder.
 * Processing pipeline:
 * 1. Dedup by chunkId (preserve first occurrence — authoritative order from Phase 3)
 * 2. Batch hydrate document metadata (documentName, pageNumber)
 * 3. Build ContextEntry list with estimated tokens
 * 4. Apply token budget with truncation
 * 5. Assign citation IDs to surviving entries
 */
@Component
public class RagContextBuilderImpl implements RagContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(RagContextBuilderImpl.class);

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;

    public RagContextBuilderImpl(DocumentMapper documentMapper, DocumentChunkMapper chunkMapper) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    @Override
    public RagContext build(List<RetrievalResult> retrievalResults, int tokenBudget) {
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be positive, got: " + tokenBudget);
        }

        if (retrievalResults == null || retrievalResults.isEmpty()) {
            return new RagContext(List.of(), 0, tokenBudget);
        }

        // 1. Dedup by chunkId — preserve first occurrence order
        List<RetrievalResult> deduped = dedupByChunkId(retrievalResults);

        if (deduped.isEmpty()) {
            return new RagContext(List.of(), 0, tokenBudget);
        }

        // 2. Batch hydrate metadata
        Map<Long, String> documentNames = loadDocumentNames(deduped);
        Map<Long, DocumentChunk> chunkMeta = loadChunkMetadata(deduped);

        // 3. Build preliminary entries (without citation IDs yet)
        List<PreliminaryEntry> preEntries = new ArrayList<>();
        for (int i = 0; i < deduped.size(); i++) {
            RetrievalResult rr = deduped.get(i);
            String docName = documentNames.getOrDefault(rr.getDocumentId(), "Unknown");
            DocumentChunk chunk = chunkMeta.get(rr.getChunkId());
            Integer pageNumber = extractPageNumber(chunk, rr);

            String content = rr.getContent() != null ? rr.getContent() : "";
            int estimatedTokens = TokenEstimator.estimate(content);

            preEntries.add(new PreliminaryEntry(
                    rr.getChunkId(), rr.getDocumentId(), docName, content,
                    rr.getChunkIndex(), rr.getSectionPath(),
                    rr.getScore(), rr.getScoreType(), pageNumber,
                    estimatedTokens));
        }

        // 4. Apply token budget
        List<PreliminaryEntry> budgeted = applyTokenBudget(preEntries, tokenBudget);

        // 5. Assign citation IDs (1-based)
        List<ContextEntry> entries = new ArrayList<>();
        for (int i = 0; i < budgeted.size(); i++) {
            PreliminaryEntry pe = budgeted.get(i);
            entries.add(new ContextEntry(
                    i + 1, pe.chunkId, pe.documentId, pe.documentName,
                    pe.content, pe.chunkIndex, pe.sectionPath,
                    pe.score, pe.scoreType, pe.pageNumber));
        }

        int totalEstimated = entries.stream()
                .mapToInt(e -> TokenEstimator.estimate(e.content()))
                .sum();

        log.debug("RagContext built: {} entries, {} estimated tokens, budget={}",
                entries.size(), totalEstimated, tokenBudget);

        return new RagContext(entries, totalEstimated, tokenBudget);
    }

    // ---- Dedup ----

    private List<RetrievalResult> dedupByChunkId(List<RetrievalResult> results) {
        Set<Long> seen = new HashSet<>();
        List<RetrievalResult> deduped = new ArrayList<>();
        for (RetrievalResult rr : results) {
            if (rr.getChunkId() != null && seen.add(rr.getChunkId())) {
                deduped.add(rr);
            }
        }
        return deduped;
    }

    // ---- Batch Hydration ----

    private Map<Long, String> loadDocumentNames(List<RetrievalResult> results) {
        Set<Long> docIds = results.stream()
                .map(RetrievalResult::getDocumentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (docIds.isEmpty()) {
            return Map.of();
        }

        List<KnowledgeDocument> docs = documentMapper.selectBatchIds(docIds);
        return docs.stream()
                .collect(Collectors.toMap(
                        KnowledgeDocument::getId,
                        KnowledgeDocument::getOriginalFileName,
                        (a, b) -> a));
    }

    private Map<Long, DocumentChunk> loadChunkMetadata(List<RetrievalResult> results) {
        Set<Long> chunkIds = results.stream()
                .map(RetrievalResult::getChunkId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (chunkIds.isEmpty()) {
            return Map.of();
        }

        List<DocumentChunk> chunks = chunkMapper.selectBatchIds(chunkIds);
        return chunks.stream()
                .collect(Collectors.toMap(
                        DocumentChunk::getId,
                        c -> c,
                        (a, b) -> a));
    }

    private Integer extractPageNumber(DocumentChunk chunk, RetrievalResult rr) {
        // Prefer chunk pageStart/pageEnd
        if (chunk != null && chunk.getPageStart() != null) {
            return chunk.getPageStart();
        }
        // No fake pageNumber from sectionPath
        return null;
    }

    // ---- Token Budget ----

    private List<PreliminaryEntry> applyTokenBudget(List<PreliminaryEntry> entries, int tokenBudget) {
        List<PreliminaryEntry> accepted = new ArrayList<>();
        int runningTotal = 0;

        for (int i = 0; i < entries.size(); i++) {
            PreliminaryEntry pe = entries.get(i);
            int entryTokens = pe.estimatedTokens;

            if (runningTotal + entryTokens <= tokenBudget) {
                // Full entry fits
                accepted.add(pe);
                runningTotal += entryTokens;
            } else if (accepted.isEmpty()) {
                // Budget too small even for first entry — truncate it
                String truncated = truncateContent(pe.content, tokenBudget);
                accepted.add(pe.withContent(truncated, TokenEstimator.estimate(truncated)));
                break;
            } else {
                // Partial fit: truncate last entry
                int remaining = tokenBudget - runningTotal;
                if (remaining > 0) {
                    String truncated = truncateContent(pe.content, remaining);
                    accepted.add(pe.withContent(truncated, TokenEstimator.estimate(truncated)));
                }
                // else: no room at all, skip this and all subsequent entries
                break;
            }
        }

        return accepted;
    }

    /**
     * Deterministic truncation: preserve as many complete characters as possible
     * while staying within the estimated token budget.
     * Uses the same TokenEstimator to ensure monotonic reduction.
     */
    private String truncateContent(String content, int maxTokens) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        if (maxTokens <= 0) {
            return "";
        }

        // Binary search for the maximum prefix that fits
        int lo = 0, hi = content.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            String prefix = content.substring(0, mid);
            if (TokenEstimator.estimate(prefix) <= maxTokens) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }

        return content.substring(0, lo);
    }

    // ---- Internal helper ----

    private static class PreliminaryEntry {
        final Long chunkId;
        final Long documentId;
        final String documentName;
        final String content;
        final int chunkIndex;
        final String sectionPath;
        final float score;
        final ScoreType scoreType;
        final Integer pageNumber;
        final int estimatedTokens;

        PreliminaryEntry(Long chunkId, Long documentId, String documentName,
                         String content, int chunkIndex, String sectionPath,
                         float score, ScoreType scoreType, Integer pageNumber,
                         int estimatedTokens) {
            this.chunkId = chunkId;
            this.documentId = documentId;
            this.documentName = documentName;
            this.content = content;
            this.chunkIndex = chunkIndex;
            this.sectionPath = sectionPath;
            this.score = score;
            this.scoreType = scoreType;
            this.pageNumber = pageNumber;
            this.estimatedTokens = estimatedTokens;
        }

        PreliminaryEntry withContent(String newContent, int newTokens) {
            return new PreliminaryEntry(chunkId, documentId, documentName,
                    newContent, chunkIndex, sectionPath, score, scoreType,
                    pageNumber, newTokens);
        }
    }
}