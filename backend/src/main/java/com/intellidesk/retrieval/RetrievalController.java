package com.intellidesk.retrieval;

import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.common.Result;
import com.intellidesk.retrieval.dto.RetrievalResultResponse;
import com.intellidesk.retrieval.dto.RetrievalSearchRequest;
import com.intellidesk.retrieval.dto.RetrievalSearchResponse;
import com.intellidesk.user.User;
import com.intellidesk.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/retrieval")
@RequiredArgsConstructor
public class RetrievalController {

    private final RetrievalService retrievalService;
    private final RetrievalScopeResolver scopeResolver;
    private final UserService userService;

    @PostMapping("/search")
    @PreAuthorize("hasAuthority('retrieval:view')")
    public Result<RetrievalSearchResponse> search(
            @PathVariable Long workspaceId,
            @Valid @RequestBody RetrievalSearchRequest request,
            Authentication authentication) {

        User user = userService.findByUsername(authentication.getName());

        // Validate request
        if (request.getQuery() == null || request.getQuery().isBlank() || request.getQuery().length() > 2000) {
            throw new BusinessException(ErrorCode.RETRIEVAL_INVALID_REQUEST);
        }
        if (request.getKnowledgeBaseIds() == null || request.getKnowledgeBaseIds().isEmpty()) {
            throw new BusinessException(ErrorCode.RETRIEVAL_INVALID_REQUEST);
        }
        if (request.getKnowledgeBaseIds().size() > 20) {
            throw new BusinessException(ErrorCode.RETRIEVAL_INVALID_REQUEST);
        }
        if (request.getDocumentIds() != null && request.getDocumentIds().size() > 100) {
            throw new BusinessException(ErrorCode.RETRIEVAL_INVALID_REQUEST);
        }

        // Validate bounds
        int candidateTopK = request.getCandidateTopK() != null ? request.getCandidateTopK() : 30;
        int topK = request.getTopK() != null ? request.getTopK() : 8;
        RetrievalMode mode = request.getMode() != null ? request.getMode() : RetrievalMode.HYBRID;
        boolean rerank = request.getRerank() != null && request.getRerank();

        if (candidateTopK < 1 || candidateTopK > 100) {
            throw new BusinessException(ErrorCode.RETRIEVAL_INVALID_REQUEST);
        }
        if (topK < 1 || topK > 20) {
            throw new BusinessException(ErrorCode.RETRIEVAL_INVALID_REQUEST);
        }
        if (topK > candidateTopK) {
            throw new BusinessException(ErrorCode.RETRIEVAL_INVALID_REQUEST);
        }

        // Resolve server-authoritative scope
        RetrievalScope scope = scopeResolver.resolve(
                workspaceId, request.getKnowledgeBaseIds(), request.getDocumentIds(), user.getId());

        // Build query
        RetrievalQuery query = new RetrievalQuery(request.getQuery(), scope);

        // Execute retrieval
        List<RetrievalResult> results;
        try {
            results = retrievalService.search(query, mode, candidateTopK, topK, rerank);
        } catch (Exception e) {
            log.error("Retrieval search failed for workspace {}: {}", workspaceId, e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("Rerank service unavailable")) {
                throw new BusinessException(ErrorCode.RERANK_UNAVAILABLE);
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        // Build response
        List<RetrievalResultResponse> resultResponses = results.stream()
                .map(r -> RetrievalResultResponse.builder()
                        .chunkId(r.getChunkId())
                        .documentId(r.getDocumentId())
                        .knowledgeBaseId(r.getKnowledgeBaseId())
                        .content(r.getContent())
                        .score(r.getScore())
                        .scoreType(r.getScoreType())
                        .chunkIndex(r.getChunkIndex())
                        .sectionPath(r.getSectionPath())
                        .retrievalSource(r.getRetrievalSource())
                        .matchedSources(r.getMatchedSources())
                        .sourceScores(r.getSourceScores())
                        .build())
                .toList();

        RetrievalSearchResponse response = RetrievalSearchResponse.builder()
                .mode(mode)
                .rerankApplied(rerank)
                .totalResults(resultResponses.size())
                .results(resultResponses)
                .build();

        return Result.success(response);
    }
}