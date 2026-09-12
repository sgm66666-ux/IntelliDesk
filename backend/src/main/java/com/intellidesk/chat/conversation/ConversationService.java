package com.intellidesk.chat.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.intellidesk.chat.message.ChatMessage;
import com.intellidesk.chat.message.ChatMessageMapper;
import com.intellidesk.chat.message.ChatMessageStatus;
import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final WorkspaceAuthorizationService workspaceAuth;

    public ConversationService(ConversationMapper conversationMapper,
                               ChatMessageMapper chatMessageMapper,
                               WorkspaceAuthorizationService workspaceAuth) {
        this.conversationMapper = conversationMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.workspaceAuth = workspaceAuth;
    }

    @Transactional
    public Conversation create(Long workspaceId, Long userId, String title) {
        // 1. require workspace member
        workspaceAuth.requireMember(workspaceId, userId);

        // 2. default title
        if (title == null || title.isBlank()) {
            title = "New Conversation";
        }
        if (title.length() > 256) {
            throw new BusinessException(ErrorCode.CHAT_INVALID_REQUEST, "标题不能超过 256 个字符");
        }

        Conversation conversation = new Conversation();
        conversation.setWorkspaceId(workspaceId);
        conversation.setUserId(userId);
        conversation.setTitle(title);
        conversation.setStatus("ACTIVE");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());

        conversationMapper.insert(conversation);
        log.debug("Created conversation id={} for user={} in workspace={}", conversation.getId(), userId, workspaceId);
        return conversation;
    }

    public List<Conversation> listByWorkspace(Long workspaceId, Long userId) {
        // 1. require workspace member
        workspaceAuth.requireMember(workspaceId, userId);

        // 2. only own conversations, exclude DELETED
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Conversation::getWorkspaceId, workspaceId)
                .eq(Conversation::getUserId, userId)
                .ne(Conversation::getStatus, "DELETED")
                .orderByDesc(Conversation::getUpdatedAt);

        return conversationMapper.selectList(wrapper);
    }

    public Conversation get(Long conversationId, Long workspaceId, Long userId) {
        return loadOwnConversation(conversationId, workspaceId, userId);
    }

    @Transactional
    public Conversation updateTitle(Long conversationId, Long workspaceId, Long userId, String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.CHAT_INVALID_REQUEST, "标题不能为空");
        }
        if (title.length() > 256) {
            throw new BusinessException(ErrorCode.CHAT_INVALID_REQUEST, "标题不能超过 256 个字符");
        }

        Conversation conversation = loadOwnConversation(conversationId, workspaceId, userId);

        conversation.setTitle(title);
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);

        log.debug("Updated conversation id={} title", conversationId);
        return conversation;
    }

    @Transactional
    public void delete(Long conversationId, Long workspaceId, Long userId) {
        Conversation conversation = loadOwnConversation(conversationId, workspaceId, userId);

        // Soft delete: set status=DELETED
        conversation.setStatus("DELETED");
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);

        log.debug("Soft-deleted conversation id={}", conversationId);
    }

    public List<ChatMessage> getMessages(Long conversationId, Long workspaceId, Long userId) {
        Conversation conversation = loadOwnConversation(conversationId, workspaceId, userId);

        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getSequenceNo);

        return chatMessageMapper.selectList(wrapper);
    }

    /**
     * Check if the conversation has any GENERATING message.
     * If so, reject new requests with CHAT_CONVERSATION_BUSY.
     */
    public void requireNotBusy(Long conversationId) {
        int count = conversationMapper.countGeneratingMessages(conversationId);
        if (count > 0) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_BUSY);
        }
    }

    /**
     * Allocate the next two sequence numbers for a USER → ASSISTANT message pair.
     * Uses SELECT FOR UPDATE to prevent concurrent allocation.
     * Must be called within a transaction.
     *
     * @return int[2] where [0] = user sequence, [1] = assistant sequence
     */
    @Transactional
    public int[] allocateNextSequencePair(Long conversationId) {
        conversationMapper.lockConversationForUpdate(conversationId);
        int maxSeq = conversationMapper.getMaxSequence(conversationId);
        return new int[]{maxSeq + 1, maxSeq + 2};
    }

    /**
     * Allocate next sequence pair and insert both USER and ASSISTANT messages in a single transaction.
     * Uses SELECT FOR UPDATE to prevent concurrent sequence allocation.
     * <p>
     * Also performs the authoritative busy check: after locking the conversation row,
     * counts GENERATING messages and rejects if any exist. This prevents TOCTOU races
     * where two concurrent requests both pass an external requireNotBusy() check.
     * <p>
     * This must be a single transaction to prevent race conditions between seq allocation and insertion.
     *
     * @param conversationId   conversation ID
     * @param userMessage      USER message (sequence_no will be set by this method)
     * @param assistantMessage ASSISTANT message (sequence_no will be set by this method)
     * @throws BusinessException CHAT_CONVERSATION_BUSY if the conversation already has a GENERATING message
     */
    @Transactional
    public void insertMessagePair(Long conversationId, ChatMessage userMessage, ChatMessage assistantMessage) {
        conversationMapper.lockConversationForUpdate(conversationId);

        // Authoritative busy check inside the lock — prevents TOCTOU race
        int generatingCount = conversationMapper.countGeneratingMessages(conversationId);
        if (generatingCount > 0) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_BUSY);
        }

        int maxSeq = conversationMapper.getMaxSequence(conversationId);
        userMessage.setSequenceNo(maxSeq + 1);
        assistantMessage.setSequenceNo(maxSeq + 2);
        chatMessageMapper.insert(userMessage);
        chatMessageMapper.insert(assistantMessage);
    }

    /**
     * Allocate the next single sequence number for a conversation.
     * Uses SELECT FOR UPDATE to prevent concurrent allocation.
     * Must be called within a transaction.
     */
    @Transactional
    public int allocateNextSequence(Long conversationId) {
        conversationMapper.lockConversationForUpdate(conversationId);
        int maxSeq = conversationMapper.getMaxSequence(conversationId);
        return maxSeq + 1;
    }

    /**
     * Insert a TOOL message with a new sequence number.
     * Must be called within a transaction.
     */
    @Transactional
    public void insertToolMessage(Long conversationId, ChatMessage toolMessage) {
        int nextSeq = allocateNextSequence(conversationId);
        toolMessage.setSequenceNo(nextSeq);
        chatMessageMapper.insert(toolMessage);
    }

    /**
     * Update a chat message with CAS guard (only if status is still GENERATING).
     * Used by orchestration service to finalize assistant messages.
     *
     * @return true if the update was applied (CAS succeeded), false if the status was no longer GENERATING
     */
    @Transactional
    public boolean updateMessageCas(ChatMessage message) {
        LambdaUpdateWrapper<ChatMessage> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ChatMessage::getId, message.getId())
                .eq(ChatMessage::getStatus, ChatMessageStatus.GENERATING.name());
        int rows = chatMessageMapper.update(message, wrapper);
        if (rows == 0) {
            log.warn("CAS update failed for message id={}: status is no longer GENERATING (already finalized by another path)", message.getId());
        }
        return rows > 0;
    }

    /**
     * TX2 finalization: update ASSISTANT message with CAS guard.
     * DB-authoritative relocation: always locks conversation and checks max sequence
     * to determine whether relocation is needed (no caller-supplied hasTools flag).
     * <p>
     * This method:
     * 1. CAS-checks that the ASSISTANT is still GENERATING
     * 2. Locks conversation row to serialize with concurrent TOOL persistence
     * 3. Reads max sequence from DB — if maxSeq > assistant.sequenceNo, TOOL messages exist, relocate
     * 4. Updates status, content, model, usage, errorCode, errorMessage
     * <p>
     * Must be called within a transaction.
     *
     * @param assistantMessage the ASSISTANT message to finalize (must have id and sequenceNo set)
     * @return true if the update was applied (CAS succeeded), false otherwise
     */
    @Transactional
    public boolean finalizeAssistant(ChatMessage assistantMessage) {
        // CAS: only update if still GENERATING
        LambdaUpdateWrapper<ChatMessage> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ChatMessage::getId, assistantMessage.getId())
                .eq(ChatMessage::getStatus, ChatMessageStatus.GENERATING.name());

        // Lock conversation to serialize with concurrent TOOL persistence and cancel attempts
        conversationMapper.lockConversationForUpdate(assistantMessage.getConversationId());

        // DB-authoritative relocation: check if TOOL messages exist after the assistant
        int maxSeq = conversationMapper.getMaxSequence(assistantMessage.getConversationId());
        Integer currentSeq = assistantMessage.getSequenceNo();
        if (currentSeq != null && maxSeq > currentSeq) {
            // TOOL messages were inserted after the assistant — relocate
            assistantMessage.setSequenceNo(maxSeq + 1);
        }
        // else: no TOOL messages, or sequenceNo not set, keep original sequence

        int rows = chatMessageMapper.update(assistantMessage, wrapper);
        if (rows == 0) {
            log.warn("CAS finalization failed for assistant message id={}: status is no longer GENERATING", assistantMessage.getId());
        }
        return rows > 0;
    }

    /**
     * Try to insert a TOOL message, gated on the assistant still being GENERATING.
     * <p>
     * Uses the conversation row lock to serialize with concurrent terminal finalization.
     * If the assistant is no longer GENERATING (already CANCELLED/FAILED/SUCCESS),
     * the TOOL insert is rejected — this prevents the invalid state:
     * ASSISTANT CANCELLED followed by TOOL.
     * <p>
     * Must be called within a transaction.
     *
     * @param conversationId    conversation ID
     * @param assistantMessageId the ID of the ASSISTANT message this TOOL belongs to
     * @param toolMessage        the TOOL message to insert (sequence_no will be set by this method)
     * @return true if the TOOL was inserted, false if the assistant is no longer GENERATING
     */
    @Transactional
    public boolean tryInsertToolMessage(Long conversationId, Long assistantMessageId, ChatMessage toolMessage) {
        // Lock conversation to serialize with concurrent terminal finalization
        conversationMapper.lockConversationForUpdate(conversationId);

        // Verify the assistant is still GENERATING
        ChatMessage assistant = chatMessageMapper.selectById(assistantMessageId);
        if (assistant == null) {
            log.warn("Assistant message id={} not found, cannot insert TOOL", assistantMessageId);
            return false;
        }
        if (!ChatMessageStatus.GENERATING.name().equals(assistant.getStatus())) {
            log.info("Assistant message id={} already terminal (status={}), rejecting TOOL insert",
                    assistantMessageId, assistant.getStatus());
            return false;
        }

        // Allocate next sequence and insert
        int maxSeq = conversationMapper.getMaxSequence(conversationId);
        toolMessage.setSequenceNo(maxSeq + 1);
        chatMessageMapper.insert(toolMessage);
        return true;
    }

    /**
     * IDOR-safe conversation loading.
     * Checks: workspace member -> conversation exists -> workspace match -> user ownership.
     * Returns 404 for any mismatch (does not leak existence).
     */
    private Conversation loadOwnConversation(Long conversationId, Long workspaceId, Long userId) {
        // 1. require workspace member
        workspaceAuth.requireMember(workspaceId, userId);

        // 2. load conversation
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND);
        }

        // 3. filter DELETED conversations
        if ("DELETED".equals(conversation.getStatus())) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND);
        }

        // 4. verify workspace match
        if (!conversation.getWorkspaceId().equals(workspaceId)) {
            // Conversation belongs to another workspace, return 404 to avoid leaking
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND);
        }

        // 5. verify user ownership
        if (!conversation.getUserId().equals(userId)) {
            // User is workspace member but not conversation owner, return 404
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND);
        }

        return conversation;
    }
}