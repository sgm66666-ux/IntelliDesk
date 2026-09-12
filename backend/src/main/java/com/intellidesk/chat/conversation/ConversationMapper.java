package com.intellidesk.chat.conversation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /**
     * Lock the conversation row to prevent concurrent sequence allocation.
     * Must be called within a transaction. Uses FOR UPDATE to serialize access.
     */
    @Select("SELECT id FROM conversation WHERE id = #{conversationId} FOR UPDATE")
    Long lockConversationForUpdate(@Param("conversationId") Long conversationId);

    /**
     * Get the max sequence_no for a conversation (without lock).
     * Must be called within the same transaction as lockConversationForUpdate.
     * Returns 0 if no messages exist.
     */
    @Select("SELECT COALESCE(MAX(sequence_no), 0) FROM chat_message WHERE conversation_id = #{conversationId}")
    int getMaxSequence(@Param("conversationId") Long conversationId);

    /**
     * Count messages with GENERATING status for a conversation.
     * Used to detect concurrent streaming requests.
     */
    @Select("SELECT COUNT(*) FROM chat_message WHERE conversation_id = #{conversationId} AND status = 'GENERATING'")
    int countGeneratingMessages(@Param("conversationId") Long conversationId);
}