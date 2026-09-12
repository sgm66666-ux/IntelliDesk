package com.intellidesk.retrieval;

import java.util.Collections;
import java.util.List;

/**
 * Immutable server-resolved retrieval scope.
 * Created only by RetrievalScopeResolver after authorization.
 */
public record RetrievalScope(
        Long workspaceId,
        List<Long> knowledgeBaseIds,
        List<Long> documentIds
) {
    public RetrievalScope {
        workspaceId = java.util.Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
        documentIds = documentIds != null ? List.copyOf(documentIds) : List.of();
    }

    public boolean hasDocumentFilter() {
        return !documentIds.isEmpty();
    }

    public static RetrievalScope of(Long workspaceId, List<Long> knowledgeBaseIds) {
        return new RetrievalScope(workspaceId, knowledgeBaseIds, List.of());
    }

    public static RetrievalScope of(Long workspaceId, List<Long> knowledgeBaseIds, List<Long> documentIds) {
        return new RetrievalScope(workspaceId, knowledgeBaseIds, documentIds);
    }
}