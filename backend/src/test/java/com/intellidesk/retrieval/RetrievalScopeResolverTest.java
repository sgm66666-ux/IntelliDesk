package com.intellidesk.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intellidesk.common.BusinessException;
import com.intellidesk.document.DocumentMapper;
import com.intellidesk.document.KnowledgeDocument;
import com.intellidesk.knowledge.KnowledgeBase;
import com.intellidesk.knowledge.KnowledgeBaseMapper;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetrievalScopeResolver")
class RetrievalScopeResolverTest {

    @Mock
    private WorkspaceAuthorizationService authService;

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private DocumentMapper documentMapper;

    private RetrievalScopeResolver resolver;

    private static final Long WORKSPACE_ID = 1L;
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        resolver = new RetrievalScopeResolver(authService, knowledgeBaseMapper, documentMapper);
    }

    @Nested
    @DisplayName("owner / member")
    class OwnerMember {

        @Test
        @DisplayName("owner can resolve scope")
        void ownerCanResolve() {
            doNothing().when(authService).requireMember(WORKSPACE_ID, USER_ID);
            KnowledgeBase kb = new KnowledgeBase();
            kb.setId(1L);
            kb.setWorkspaceId(WORKSPACE_ID);
            when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(kb));

            RetrievalScope scope = resolver.resolve(WORKSPACE_ID, List.of(1L), null, USER_ID);

            assertThat(scope.workspaceId()).isEqualTo(WORKSPACE_ID);
            assertThat(scope.knowledgeBaseIds()).containsExactly(1L);
        }

        @Test
        @DisplayName("member can resolve scope")
        void memberCanResolve() {
            doNothing().when(authService).requireMember(WORKSPACE_ID, USER_ID);
            KnowledgeBase kb = new KnowledgeBase();
            kb.setId(1L);
            kb.setWorkspaceId(WORKSPACE_ID);
            when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(kb));

            RetrievalScope scope = resolver.resolve(WORKSPACE_ID, List.of(1L), null, USER_ID);

            assertThat(scope.workspaceId()).isEqualTo(WORKSPACE_ID);
        }
    }

    @Nested
    @DisplayName("outsider")
    class Outsider {

        @Test
        @DisplayName("outsider gets 403 before KB existence check")
        void outsiderBlocked() {
            doThrow(new BusinessException(
                    com.intellidesk.common.ErrorCode.WORKSPACE_ACCESS_DENIED))
                    .when(authService).requireMember(WORKSPACE_ID, USER_ID);

            assertThatThrownBy(() -> resolver.resolve(WORKSPACE_ID, List.of(1L), null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(com.intellidesk.common.ErrorCode.WORKSPACE_ACCESS_DENIED.getCode());

            verify(knowledgeBaseMapper, never()).selectList(any());
        }
    }

    @Nested
    @DisplayName("cross-workspace")
    class CrossWorkspace {

        @Test
        @DisplayName("KB outside workspace returns 404")
        void kbOutsideWorkspace() {
            doNothing().when(authService).requireMember(WORKSPACE_ID, USER_ID);
            // No KB found for this workspace
            when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> resolver.resolve(WORKSPACE_ID, List.of(999L), null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(com.intellidesk.common.ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode());
        }
    }

    @Nested
    @DisplayName("cross-KB")
    class CrossKB {

        @Test
        @DisplayName("document outside authorized KBs returns 404")
        void documentOutsideKB() {
            doNothing().when(authService).requireMember(WORKSPACE_ID, USER_ID);
            KnowledgeBase kb = new KnowledgeBase();
            kb.setId(1L);
            kb.setWorkspaceId(WORKSPACE_ID);
            when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(kb));
            // Document not found in authorized KBs
            when(documentMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> resolver.resolve(WORKSPACE_ID, List.of(1L), List.of(999L), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(com.intellidesk.common.ErrorCode.DOCUMENT_NOT_FOUND.getCode());
        }
    }

    @Nested
    @DisplayName("document IDOR")
    class DocumentIDOR {

        @Test
        @DisplayName("document from another workspace is not exposed")
        void documentFromAnotherWorkspace() {
            doNothing().when(authService).requireMember(WORKSPACE_ID, USER_ID);
            KnowledgeBase kb = new KnowledgeBase();
            kb.setId(1L);
            kb.setWorkspaceId(WORKSPACE_ID);
            when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(kb));
            // Document exists but belongs to a KB not in the authorized set
            when(documentMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> resolver.resolve(WORKSPACE_ID, List.of(1L), List.of(888L), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(com.intellidesk.common.ErrorCode.DOCUMENT_NOT_FOUND.getCode());
        }
    }

    @Nested
    @DisplayName("mixed valid/invalid scope")
    class MixedScope {

        @Test
        @DisplayName("mixed valid and invalid KB IDs returns 404")
        void mixedKbIds() {
            doNothing().when(authService).requireMember(WORKSPACE_ID, USER_ID);
            KnowledgeBase kb = new KnowledgeBase();
            kb.setId(1L);
            kb.setWorkspaceId(WORKSPACE_ID);
            when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(kb)); // Only 1 KB found, but 2 requested

            assertThatThrownBy(() -> resolver.resolve(WORKSPACE_ID, List.of(1L, 999L), null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(com.intellidesk.common.ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("mixed valid and invalid document IDs returns 404")
        void mixedDocumentIds() {
            doNothing().when(authService).requireMember(WORKSPACE_ID, USER_ID);
            KnowledgeBase kb = new KnowledgeBase();
            kb.setId(1L);
            kb.setWorkspaceId(WORKSPACE_ID);
            when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(kb));
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setId(10L);
            doc.setKnowledgeBaseId(1L);
            when(documentMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(doc)); // Only 1 doc found, but 2 requested

            assertThatThrownBy(() -> resolver.resolve(WORKSPACE_ID, List.of(1L), List.of(10L, 999L), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(com.intellidesk.common.ErrorCode.DOCUMENT_NOT_FOUND.getCode());
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("null KB IDs throws error")
        void nullKbIds() {
            doNothing().when(authService).requireMember(WORKSPACE_ID, USER_ID);

            assertThatThrownBy(() -> resolver.resolve(WORKSPACE_ID, null, null, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("empty KB IDs throws error")
        void emptyKbIds() {
            doNothing().when(authService).requireMember(WORKSPACE_ID, USER_ID);

            assertThatThrownBy(() -> resolver.resolve(WORKSPACE_ID, List.of(), null, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("more than 20 KB IDs throws error")
        void tooManyKbIds() {
            doNothing().when(authService).requireMember(WORKSPACE_ID, USER_ID);

            List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, 21).boxed().toList();
            assertThatThrownBy(() -> resolver.resolve(WORKSPACE_ID, tooMany, null, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("more than 100 document IDs throws error")
        void tooManyDocumentIds() {
            doNothing().when(authService).requireMember(WORKSPACE_ID, USER_ID);
            KnowledgeBase kb = new KnowledgeBase();
            kb.setId(1L);
            kb.setWorkspaceId(WORKSPACE_ID);
            when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(kb));

            List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();
            assertThatThrownBy(() -> resolver.resolve(WORKSPACE_ID, List.of(1L), tooMany, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }
}