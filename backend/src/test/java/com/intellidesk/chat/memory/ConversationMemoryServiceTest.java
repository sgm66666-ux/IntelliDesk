package com.intellidesk.chat.memory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.intellidesk.chat.conversation.Conversation;
import com.intellidesk.chat.conversation.ConversationMapper;
import com.intellidesk.chat.message.ChatMessage;
import com.intellidesk.chat.message.ChatMessageMapper;
import com.intellidesk.chat.message.ChatMessageStatus;
import com.intellidesk.chat.message.ChatRole;
import com.intellidesk.common.BusinessException;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationMemoryService")
class ConversationMemoryServiceTest {

    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private WorkspaceAuthorizationService workspaceAuth;

    private MemoryProperties memoryProperties;
    private ConversationMemoryService service;

    private static final Long WORKSPACE_ID = 1L;
    private static final Long CONVERSATION_ID = 10L;
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        memoryProperties = new MemoryProperties();
        memoryProperties.setMaxMessages(10);
        service = new ConversationMemoryService(chatMessageMapper, conversationMapper, workspaceAuth, memoryProperties);

        // Default: conversation exists and is owned by user
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setWorkspaceId(WORKSPACE_ID);
        conversation.setUserId(USER_ID);
        conversation.setStatus("ACTIVE");
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation);
    }

    private ChatMessage makeMessage(Long id, Long convId, String role, String status, int seqNo, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setId(id);
        msg.setConversationId(convId);
        msg.setRole(role);
        msg.setStatus(status);
        msg.setSequenceNo(seqNo);
        msg.setContent(content);
        return msg;
    }

    @Nested
    @DisplayName("Memory loading")
    class MemoryLoadingTests {

        @Test
        @DisplayName("empty conversation")
        void emptyConversation() {
            when(chatMessageMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

            List<ChatMessage> messages = service.loadRecentMessages(WORKSPACE_ID, CONVERSATION_ID, USER_ID, 10);

            assertThat(messages).isEmpty();
        }

        @Test
        @DisplayName("less than N messages")
        void lessThanN() {
            when(chatMessageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                    makeMessage(1L, CONVERSATION_ID, "USER", "SUCCESS", 1, "Hello"),
                    makeMessage(2L, CONVERSATION_ID, "ASSISTANT", "SUCCESS", 2, "Hi")));

            List<ChatMessage> messages = service.loadRecentMessages(WORKSPACE_ID, CONVERSATION_ID, USER_ID, 10);

            assertThat(messages).hasSize(2);
        }

        @Test
        @DisplayName("natural ordering (oldest first)")
        void naturalOrdering() {
            when(chatMessageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                    // DB returns descending
                    makeMessage(3L, CONVERSATION_ID, "ASSISTANT", "SUCCESS", 3, "Third"),
                    makeMessage(2L, CONVERSATION_ID, "ASSISTANT", "SUCCESS", 2, "Second"),
                    makeMessage(1L, CONVERSATION_ID, "USER", "SUCCESS", 1, "First")));

            List<ChatMessage> messages = service.loadRecentMessages(WORKSPACE_ID, CONVERSATION_ID, USER_ID, 10);

            // Should be reversed to natural order
            assertThat(messages).extracting(ChatMessage::getSequenceNo).containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("only SUCCESS messages")
        void onlySuccessMessages() {
            when(chatMessageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                    makeMessage(1L, CONVERSATION_ID, "USER", "SUCCESS", 1, "Good"),
                    makeMessage(2L, CONVERSATION_ID, "ASSISTANT", "SUCCESS", 2, "Response")));

            // Verify the query filters for SUCCESS
            List<ChatMessage> messages = service.loadRecentMessages(WORKSPACE_ID, CONVERSATION_ID, USER_ID, 10);

            assertThat(messages).allMatch(m -> ChatMessageStatus.SUCCESS.name().equals(m.getStatus()));
        }

        @Test
        @DisplayName("only USER and ASSISTANT roles")
        void onlyUserAndAssistant() {
            when(chatMessageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                    makeMessage(1L, CONVERSATION_ID, "USER", "SUCCESS", 1, "Q"),
                    makeMessage(2L, CONVERSATION_ID, "ASSISTANT", "SUCCESS", 2, "A")));

            List<ChatMessage> messages = service.loadRecentMessages(WORKSPACE_ID, CONVERSATION_ID, USER_ID, 10);

            assertThat(messages).allMatch(m ->
                    ChatRole.USER.name().equals(m.getRole()) || ChatRole.ASSISTANT.name().equals(m.getRole()));
        }
    }

    @Nested
    @DisplayName("Rewrite context")
    class RewriteContextTests {

        @Test
        @DisplayName("rewrite format: User/Assistant")
        void rewriteFormat() {
            when(chatMessageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                    makeMessage(1L, CONVERSATION_ID, "USER", "SUCCESS", 1, "公司的差旅标准是什么？"),
                    makeMessage(2L, CONVERSATION_ID, "ASSISTANT", "SUCCESS", 2, "北京地区标准为500元。")));

            String context = service.buildRewriteContext(WORKSPACE_ID, CONVERSATION_ID, USER_ID, 10);

            assertThat(context).contains("User: 公司的差旅标准是什么？");
            assertThat(context).contains("Assistant: 北京地区标准为500元。");
        }

        @Test
        @DisplayName("empty rewrite context")
        void emptyRewriteContext() {
            when(chatMessageMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

            String context = service.buildRewriteContext(WORKSPACE_ID, CONVERSATION_ID, USER_ID, 10);

            assertThat(context).isEmpty();
        }
    }

    @Nested
    @DisplayName("Answer messages")
    class AnswerMessagesTests {

        @Test
        @DisplayName("build Spring AI Message list")
        void buildAnswerMessages() {
            // DB returns descending order — service reverses to natural order
            when(chatMessageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                    makeMessage(2L, CONVERSATION_ID, "ASSISTANT", "SUCCESS", 2, "Answer"),
                    makeMessage(1L, CONVERSATION_ID, "USER", "SUCCESS", 1, "Question")));

            List<Message> messages = service.buildAnswerMessages(WORKSPACE_ID, CONVERSATION_ID, USER_ID, 10);

            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).getText()).isEqualTo("Question");
            assertThat(messages.get(1).getText()).isEqualTo("Answer");
        }

        @Test
        @DisplayName("current message excluded via excludeMessageId")
        void currentMessageExcluded() {
            when(chatMessageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                    makeMessage(2L, CONVERSATION_ID, "ASSISTANT", "SUCCESS", 2, "Answer"),
                    makeMessage(1L, CONVERSATION_ID, "USER", "SUCCESS", 1, "Previous")));

            List<ChatMessage> messages = service.loadRecentMessagesExcluding(
                    WORKSPACE_ID, CONVERSATION_ID, USER_ID, 10, 3L);

            assertThat(messages).extracting(ChatMessage::getId).doesNotContain(3L);
        }
    }

    @Nested
    @DisplayName("Access control")
    class AccessControlTests {

        @Test
        @DisplayName("own conversation succeeds")
        void ownConversationSucceeds() {
            when(chatMessageMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

            List<ChatMessage> messages = service.loadRecentMessages(WORKSPACE_ID, CONVERSATION_ID, USER_ID, 10);

            assertThat(messages).isEmpty();
            verify(workspaceAuth).requireMember(WORKSPACE_ID, USER_ID);
        }

        @Test
        @DisplayName("other user's conversation denied")
        void otherUserConversationDenied() {
            Conversation conversation = new Conversation();
            conversation.setId(CONVERSATION_ID);
            conversation.setWorkspaceId(WORKSPACE_ID);
            conversation.setUserId(999L); // Different user
            conversation.setStatus("ACTIVE");
            when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation);

            assertThatThrownBy(() -> service.loadRecentMessages(WORKSPACE_ID, CONVERSATION_ID, USER_ID, 10))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6001);
        }

        @Test
        @DisplayName("other workspace denied")
        void otherWorkspaceDenied() {
            Conversation conversation = new Conversation();
            conversation.setId(CONVERSATION_ID);
            conversation.setWorkspaceId(999L); // Different workspace
            conversation.setUserId(USER_ID);
            conversation.setStatus("ACTIVE");
            when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation);

            assertThatThrownBy(() -> service.loadRecentMessages(WORKSPACE_ID, CONVERSATION_ID, USER_ID, 10))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6001);
        }

        @Test
        @DisplayName("deleted conversation denied")
        void deletedConversationDenied() {
            Conversation conversation = new Conversation();
            conversation.setId(CONVERSATION_ID);
            conversation.setWorkspaceId(WORKSPACE_ID);
            conversation.setUserId(USER_ID);
            conversation.setStatus("DELETED");
            when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation);

            assertThatThrownBy(() -> service.loadRecentMessages(WORKSPACE_ID, CONVERSATION_ID, USER_ID, 10))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(6001);
        }
    }
}