package com.intellidesk.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.document.DocumentMapper;
import com.intellidesk.document.KnowledgeDocument;
import com.intellidesk.knowledge.KnowledgeBase;
import com.intellidesk.knowledge.KnowledgeBaseMapper;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalScopeResolver {

    private final WorkspaceAuthorizationService authService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;

    /**
     * Resolve server-authoritative retrieval scope.
     * 1. requireMember(workspaceId)
     * 2. validate all KB IDs belong to workspace
     * 3. validate optional document IDs belong to authorized KBs
     * 4. build immutable RetrievalScope
     */
    public RetrievalScope resolve(Long workspaceId, List<Long> knowledgeBaseIds,
                                  List<Long> documentIds, Long userId) {
        // 1. Member check
        authService.requireMember(workspaceId, userId);

        // 2. Validate KB IDs belong to workspace
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            throw new BusinessException(ErrorCode.RETRIEVAL_SCOPE_EMPTY);
        }
        if (knowledgeBaseIds.size() > 20) {
            throw new BusinessException(ErrorCode.RETRIEVAL_INVALID_REQUEST);
        }

        List<KnowledgeBase> kbs = knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .in(KnowledgeBase::getId, knowledgeBaseIds)
                        .eq(KnowledgeBase::getWorkspaceId, workspaceId));
        if (kbs.size() != knowledgeBaseIds.size()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        // 3. Validate optional document IDs
        if (documentIds != null && !documentIds.isEmpty()) {
            if (documentIds.size() > 100) {
                throw new BusinessException(ErrorCode.RETRIEVAL_INVALID_REQUEST);
            }
            List<KnowledgeDocument> docs = documentMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeDocument>()
                            .in(KnowledgeDocument::getId, documentIds)
                            .in(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseIds));
            if (docs.size() != documentIds.size()) {
                throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
            }
        }

        return new RetrievalScope(workspaceId, knowledgeBaseIds, documentIds);
    }
}