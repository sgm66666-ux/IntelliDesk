package com.intellidesk.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.common.dto.PageResponse;
import com.intellidesk.document.DocumentMapper;
import com.intellidesk.document.KnowledgeDocument;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeBaseService extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase> {

    private static final String DEFAULT_CHUNK_STRATEGY = "RECURSIVE";
    private static final int DEFAULT_CHUNK_SIZE = 1000;
    private static final int DEFAULT_CHUNK_OVERLAP = 150;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper,
                                DocumentMapper documentMapper,
                                WorkspaceAuthorizationService workspaceAuthorizationService) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.documentMapper = documentMapper;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
    }

    @Transactional
    public KnowledgeBase createKnowledgeBase(Long workspaceId, Long userId,
                                             String name, String description,
                                             String chunkStrategy, Integer chunkSize, Integer chunkOverlap) {
        workspaceAuthorizationService.requireOwner(workspaceId, userId);

        String normalizedName = normalizeName(name);
        validateName(normalizedName);
        ChunkConfig config = resolveChunkConfig(chunkStrategy, chunkSize, chunkOverlap);
        validateChunkConfig(config);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setWorkspaceId(workspaceId);
        kb.setName(normalizedName);
        kb.setDescription(description);
        kb.setChunkStrategy(config.strategy);
        kb.setChunkSize(config.size);
        kb.setChunkOverlap(config.overlap);
        kb.setStatus("ACTIVE");
        kb.setCreatedBy(userId);

        try {
            save(kb);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NAME_ALREADY_EXISTS);
        }
        return kb;
    }

    public PageResponse<KnowledgeBase> listKnowledgeBases(Long workspaceId, Long userId,
                                                           int page, int size, String keyword) {
        workspaceAuthorizationService.requireMember(workspaceId, userId);

        if (page < 1) {
            page = 1;
        }
        if (size < 1 || size > 100) {
            size = 20;
        }

        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBase::getWorkspaceId, workspaceId);
        if (StringUtils.hasText(keyword)) {
            wrapper.like(KnowledgeBase::getName, keyword.trim());
        }

        long total = knowledgeBaseMapper.selectCount(wrapper);
        wrapper.orderByDesc(KnowledgeBase::getCreatedAt);
        wrapper.last("LIMIT " + size + " OFFSET " + ((long) (page - 1) * size));

        return PageResponse.<KnowledgeBase>builder()
                .items(knowledgeBaseMapper.selectList(wrapper))
                .page(page)
                .size(size)
                .total(total)
                .build();
    }

    public KnowledgeBase getKnowledgeBase(Long workspaceId, Long knowledgeBaseId, Long userId) {
        workspaceAuthorizationService.requireMember(workspaceId, userId);
        KnowledgeBase kb = getById(knowledgeBaseId);
        if (kb == null || !kb.getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return kb;
    }

    @Transactional
    public KnowledgeBase updateKnowledgeBase(Long workspaceId, Long knowledgeBaseId, Long userId,
                                              String name, String description,
                                              String chunkStrategy, Integer chunkSize, Integer chunkOverlap) {
        workspaceAuthorizationService.requireOwner(workspaceId, userId);

        KnowledgeBase kb = getById(knowledgeBaseId);
        if (kb == null || !kb.getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        if (StringUtils.hasText(name)) {
            String normalizedName = normalizeName(name);
            validateName(normalizedName);
            kb.setName(normalizedName);
        }
        if (description != null) {
            kb.setDescription(description);
        }
        ChunkConfig config = resolveChunkConfig(chunkStrategy, chunkSize, chunkOverlap);
        validateChunkConfig(config);
        kb.setChunkStrategy(config.strategy);
        kb.setChunkSize(config.size);
        kb.setChunkOverlap(config.overlap);

        try {
            updateById(kb);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NAME_ALREADY_EXISTS);
        }
        return kb;
    }

    @Transactional
    public void deleteKnowledgeBase(Long workspaceId, Long knowledgeBaseId, Long userId) {
        workspaceAuthorizationService.requireOwner(workspaceId, userId);

        KnowledgeBase kb = getById(knowledgeBaseId);
        if (kb == null || !kb.getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        long documentCount = documentMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeDocument>().eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId));
        if (documentCount > 0) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_EMPTY);
        }

        removeById(knowledgeBaseId);
    }

    private String normalizeName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NAME_INVALID);
        }
        return trimmed;
    }

    private void validateName(String name) {
        if (name == null || name.length() > 100) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NAME_INVALID);
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NAME_INVALID);
        }
    }

    private ChunkConfig resolveChunkConfig(String strategy, Integer size, Integer overlap) {
        String resolvedStrategy = StringUtils.hasText(strategy) ? strategy.trim().toUpperCase() : DEFAULT_CHUNK_STRATEGY;
        int resolvedSize = size != null ? size : DEFAULT_CHUNK_SIZE;
        int resolvedOverlap = overlap != null ? overlap : DEFAULT_CHUNK_OVERLAP;
        return new ChunkConfig(resolvedStrategy, resolvedSize, resolvedOverlap);
    }

    private void validateChunkConfig(ChunkConfig config) {
        if (!"FIXED_SIZE".equals(config.strategy) && !"RECURSIVE".equals(config.strategy)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_CHUNK_CONFIG_INVALID);
        }
        if (config.size < 100 || config.size > 4000) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_CHUNK_CONFIG_INVALID);
        }
        if (config.overlap < 0 || config.overlap > 1000 || config.overlap >= config.size) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_CHUNK_CONFIG_INVALID);
        }
    }

    private record ChunkConfig(String strategy, int size, int overlap) {
    }
}
