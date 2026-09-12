package com.intellidesk.chat.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intellidesk.chat.conversation.Conversation;
import com.intellidesk.chat.conversation.ConversationMapper;
import com.intellidesk.chat.message.ChatMessage;
import com.intellidesk.chat.message.ChatMessageMapper;
import com.intellidesk.chat.message.ChatMessageStatus;
import com.intellidesk.chat.message.ChatRole;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Conversation memory service — last-N messages window.
 * Authority: PostgreSQL. No Redis, no summary, no vector memory.
 * <p>
 * Isolation: requires workspace + conversation ownership validation.
 */
@Service
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    private final ChatMessageMapper chatMessageMapper;
    private final ConversationMapper conversationMapper;
    private final WorkspaceAuthorizationService workspaceAuth;
    private final MemoryProperties memoryProperties;

    public ConversationMemoryService(ChatMessageMapper chatMessageMapper,
                                     ConversationMapper conversationMapper,
                                     WorkspaceAuthorizationService workspaceAuth,
                                     MemoryProperties memoryProperties) {
        this.chatMessageMapper = chatMessageMapper;
        this.conversationMapper = conversationMapper;
        this.workspaceAuth = workspaceAuth;
        this.memoryProperties = memoryProperties;
    }

    /**
     * Load recent messages for conversation memory.
     * Only returns SUCCESS USER + ASSISTANT messages in natural order (oldest first).
     *
     * @param workspaceId    workspace scope
     * @param conversationId conversation ID
     * @param userId         current user
     * @param maxMessages    max number of messages to return
     * @return list of recent messages in natural order
     */
    public List<ChatMessage> loadRecentMessages(Long workspaceId, Long conversationId,
                                                 Long userId, int maxMessages) {
        verifyAccess(workspaceId, conversationId, userId);

        if (maxMessages <= 0) {
            return List.of();
        }

        List<ChatMessage> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .eq(ChatMessage::getStatus, ChatMessageStatus.SUCCESS.name())
                        .in(ChatMessage::getRole, ChatRole.USER.name(), ChatRole.ASSISTANT.name())
                        .orderByDesc(ChatMessage::getSequenceNo)
                        .last("LIMIT " + maxMessages));

        // Reverse to natural order (oldest → newest)
        // Use mutable copy because MyBatis-Plus may return an unmodifiable list
        List<ChatMessage> reversed = new ArrayList<>(messages);
        Collections.reverse(reversed);
        return reversed;
    }

    /**
     * Load recent messages, excluding a specific message ID.
     * Used to avoid duplicating the current user message in memory.
     */
    public List<ChatMessage> loadRecentMessagesExcluding(Long workspaceId, Long conversationId,
                                                          Long userId, int maxMessages,
                                                          Long excludeMessageId) {
        verifyAccess(workspaceId, conversationId, userId);

        if (maxMessages <= 0) {
            return List.of();
        }

        List<ChatMessage> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .eq(ChatMessage::getStatus, ChatMessageStatus.SUCCESS.name())
                        .in(ChatMessage::getRole, ChatRole.USER.name(), ChatRole.ASSISTANT.name())
                        .ne(excludeMessageId != null, ChatMessage::getId, excludeMessageId)
                        .orderByDesc(ChatMessage::getSequenceNo)
                        .last("LIMIT " + maxMessages));

        List<ChatMessage> reversed = new ArrayList<>(messages);
        Collections.reverse(reversed);
        return reversed;
    }

    /**
     * Build rewrite context string from recent messages.
     * Format: "User: ...\nAssistant: ..."
     */
    public String buildRewriteContext(Long workspaceId, Long conversationId,
                                       Long userId, int maxMessages) {
        List<ChatMessage> messages = loadRecentMessages(workspaceId, conversationId, userId, maxMessages);

        if (messages.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (ChatRole.USER.name().equals(msg.getRole())) {
                sb.append("User: ").append(msg.getContent()).append("\n");
            } else if (ChatRole.ASSISTANT.name().equals(msg.getRole())) {
                sb.append("Assistant: ").append(msg.getContent()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Build Spring AI Message list for answer generation.
     * Converts ChatMessage entities to Spring AI UserMessage/AssistantMessage.
     */
    public List<Message> buildAnswerMessages(Long workspaceId, Long conversationId,
                                              Long userId, int maxMessages) {
        List<ChatMessage> messages = loadRecentMessages(workspaceId, conversationId, userId, maxMessages);

        List<Message> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if (ChatRole.USER.name().equals(msg.getRole())) {
                result.add(new UserMessage(msg.getContent()));
            } else if (ChatRole.ASSISTANT.name().equals(msg.getRole())) {
                result.add(new AssistantMessage(msg.getContent()));
            }
        }
        return result;
    }

    /**
     * Build Spring AI Message list for answer generation, excluding the current message.
     * Prevents the current query from appearing twice in the prompt.
     */
    public List<Message> buildAnswerMessagesExcluding(Long workspaceId, Long conversationId,
                                                       Long userId, int maxMessages,
                                                       Long excludeMessageId) {
        List<ChatMessage> messages = loadRecentMessagesExcluding(
                workspaceId, conversationId, userId, maxMessages, excludeMessageId);

        List<Message> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if (ChatRole.USER.name().equals(msg.getRole())) {
                result.add(new UserMessage(msg.getContent()));
            } else if (ChatRole.ASSISTANT.name().equals(msg.getRole())) {
                result.add(new AssistantMessage(msg.getContent()));
            }
        }
        return result;
    }

    /**
     * Load recent messages with default maxMessages from config.
     */
    public List<ChatMessage> loadRecentMessages(Long workspaceId, Long conversationId, Long userId) {
        return loadRecentMessages(workspaceId, conversationId, userId, memoryProperties.getMaxMessages());
    }

    /**
     * Build rewrite context with default maxMessages from config.
     */
    public String buildRewriteContext(Long workspaceId, Long conversationId, Long userId) {
        return buildRewriteContext(workspaceId, conversationId, userId, memoryProperties.getMaxMessages());
    }

    /**
     * Build answer messages with default maxMessages from config.
     */
    public List<Message> buildAnswerMessages(Long workspaceId, Long conversationId, Long userId) {
        return buildAnswerMessages(workspaceId, conversationId, userId, memoryProperties.getMaxMessages());
    }

    // ---- Access Control ----

    private void verifyAccess(Long workspaceId, Long conversationId, Long userId) {
        // 1. require workspace member
        workspaceAuth.requireMember(workspaceId, userId);

        // 2. load conversation
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND);
        }

        // 3. filter DELETED
        if ("DELETED".equals(conversation.getStatus())) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND);
        }

        // 4. verify workspace match
        if (!conversation.getWorkspaceId().equals(workspaceId)) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND);
        }

        // 5. verify user ownership
        if (!conversation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND);
        }
    }
}