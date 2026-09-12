package com.intellidesk.chat.conversation;

import com.intellidesk.chat.message.ChatMessage;
import com.intellidesk.chat.message.ChatMessageMapper;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationService")
class ConversationServiceTest {

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @Mock
    private WorkspaceAuthorizationService workspaceAuth;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversationMapper, chatMessageMapper, workspaceAuth);
    }

    private Conversation createConversation(Long id, Long workspaceId, Long userId, String title) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setWorkspaceId(workspaceId);
        c.setUserId(userId);
        c.setTitle(title);
        c.setStatus("ACTIVE");
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        return c;
    }

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("member creates conversation with title")
        void memberCreatesWithTitle() {
            doNothing().when(workspaceAuth).requireMember(1L, 100L);
            when(conversationMapper.insert(any(Conversation.class))).thenReturn(1);

            Conversation result = service.create(1L, 100L, "My Conversation");

            assertThat(result.getTitle()).isEqualTo("My Conversation");
            assertThat(result.getWorkspaceId()).isEqualTo(1L);
            assertThat(result.getUserId()).isEqualTo(100L);
            assertThat(result.getStatus()).isEqualTo("ACTIVE");
            assertThat(result.getCreatedAt()).isNotNull();
            assertThat(result.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("member creates conversation with default title")
        void memberCreatesWithDefaultTitle() {
            doNothing().when(workspaceAuth).requireMember(1L, 100L);
            when(conversationMapper.insert(any(Conversation.class))).thenReturn(1);

            Conversation result = service.create(1L, 100L, null);

            assertThat(result.getTitle()).isEqualTo("New Conversation");
        }

        @Test
        @DisplayName("member creates conversation with blank title")
        void memberCreatesWithBlankTitle() {
            doNothing().when(workspaceAuth).requireMember(1L, 100L);
            when(conversationMapper.insert(any(Conversation.class))).thenReturn(1);

            Conversation result = service.create(1L, 100L, "   ");

            assertThat(result.getTitle()).isEqualTo("New Conversation");
        }

        @Test
        @DisplayName("non-member is rejected with 403")
        void nonMemberRejected() {
            doThrow(new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED))
                    .when(workspaceAuth).requireMember(1L, 999L);

            assertThatThrownBy(() -> service.create(1L, 999L, "Test"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(2002);
        }

        @Test
        @DisplayName("title exceeds max length")
        void titleExceedsMaxLength() {
            doNothing().when(workspaceAuth).requireMember(1L, 100L);
            String longTitle = "a".repeat(257);

            assertThatThrownBy(() -> service.create(1L, 100L, longTitle))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6003);
        }
    }

    @Nested
    @DisplayName("list")
    class ListTests {

        @Test
        @DisplayName("member lists own conversations")
        void memberListsOwn() {
            doNothing().when(workspaceAuth).requireMember(1L, 100L);
            List<Conversation> conversations = List.of(
                    createConversation(1L, 1L, 100L, "Conv 1"),
                    createConversation(2L, 1L, 100L, "Conv 2")
            );
            when(conversationMapper.selectList(any())).thenReturn(conversations);

            List<Conversation> result = service.listByWorkspace(1L, 100L);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("non-member is rejected")
        void nonMemberRejected() {
            doThrow(new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED))
                    .when(workspaceAuth).requireMember(1L, 999L);

            assertThatThrownBy(() -> service.listByWorkspace(1L, 999L))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("get")
    class GetTests {

        @Test
        @DisplayName("owner gets own conversation")
        void ownerGetsOwn() {
            doNothing().when(workspaceAuth).requireMember(1L, 100L);
            Conversation c = createConversation(1L, 1L, 100L, "My Conv");
            when(conversationMapper.selectById(1L)).thenReturn(c);

            Conversation result = service.get(1L, 1L, 100L);
            assertThat(result.getTitle()).isEqualTo("My Conv");
        }

        @Test
        @DisplayName("other user's conversation returns 404")
        void otherUserConversationReturns404() {
            doNothing().when(workspaceAuth).requireMember(1L, 200L);
            Conversation c = createConversation(1L, 1L, 100L, "Owner's Conv");
            when(conversationMapper.selectById(1L)).thenReturn(c);

            assertThatThrownBy(() -> service.get(1L, 1L, 200L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6001);
        }

        @Test
        @DisplayName("cross-workspace conversation returns 404")
        void crossWorkspaceReturns404() {
            doNothing().when(workspaceAuth).requireMember(1L, 100L);
            Conversation c = createConversation(1L, 2L, 100L, "Other WS");
            when(conversationMapper.selectById(1L)).thenReturn(c);

            assertThatThrownBy(() -> service.get(1L, 1L, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6001);
        }

        @Test
        @DisplayName("non-existent conversation returns 404")
        void nonExistentReturns404() {
            doNothing().when(workspaceAuth).requireMember(1L, 100L);
            when(conversationMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> service.get(999L, 1L, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6001);
        }

        @Test
        @DisplayName("guessed ID returns 404")
        void guessedIdReturns404() {
            doNothing().when(workspaceAuth).requireMember(1L, 100L);
            when(conversationMapper.selectById(99999L)).thenReturn(null);

            assertThatThrownBy(() -> service.get(99999L, 1L, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6001);
        }

        @Test
        @DisplayName("deleted conversation returns 404")
        void deletedConversationReturns404() {
            doNothing().when(workspaceAuth).requireMember(1L, 100L);
            Conversation c = createConversation(1L, 1L, 100L, "Deleted Conv");
            c.setStatus("DELETED");
            when(conversationMapper.selectById(1L)).thenReturn(c);

            assertThatThrownBy(() -> service.get(1L, 1L, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6001);
        }
    }

    @Nested
    @DisplayName("update")
    class UpdateTests {

        @Test
        @DisplayName("owner renames conversation")
        void ownerRenames() {
            doNothing().when(workspaceAuth).requireMember(1L, 100L);
            Conversation c = createConversation(1L, 1L, 100L, "Old Title");
            when(conversationMapper.selectById(1L)).thenReturn(c);
            when(conversationMapper.updateById(any(Conversation.class))).thenReturn(1);

            Conversation result = service.updateTitle(1L, 1L, 100L, "New Title");
            assertThat(result.getTitle()).isEqualTo("New Title");
            assertThat(result.getUpdatedAt()).isAfterOrEqualTo(c.getCreatedAt());
        }

        @Test
        @DisplayName("blank rename is rejected")
        void blankRenameRejected() {
            assertThatThrownBy(() -> service.updateTitle(1L, 1L, 100L, "   "))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6003);
        }

        @Test
        @DisplayName("rename too long is rejected")
        void renameTooLongRejected() {
            String longTitle = "a".repeat(257);
            assertThatThrownBy(() -> service.updateTitle(1L, 1L, 100L, longTitle))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6003);
        }

        @Test
        @DisplayName("other user cannot rename")
        void otherUserCannotRename() {
            doNothing().when(workspaceAuth).requireMember(1L, 200L);
            Conversation c = createConversation(1L, 1L, 100L, "Owner's");
            when(conversationMapper.selectById(1L)).thenReturn(c);

            assertThatThrownBy(() -> service.updateTitle(1L, 1L, 200L, "Hacked"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6001);
        }
    }

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("owner soft-deletes conversation")
        void ownerSoftDeletes() {
            doNothing().when(workspaceAuth).requireMember(1L, 100L);
            Conversation c = createConversation(1L, 1L, 100L, "To Delete");
            when(conversationMapper.selectById(1L)).thenReturn(c);
            when(conversationMapper.updateById(any(Conversation.class))).thenReturn(1);

            service.delete(1L, 1L, 100L);

            assertThat(c.getStatus()).isEqualTo("DELETED");
        }

        @Test
        @DisplayName("other user cannot delete")
        void otherUserCannotDelete() {
            doNothing().when(workspaceAuth).requireMember(1L, 200L);
            Conversation c = createConversation(1L, 1L, 100L, "Owner's");
            when(conversationMapper.selectById(1L)).thenReturn(c);

            assertThatThrownBy(() -> service.delete(1L, 1L, 200L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6001);
        }
    }
}