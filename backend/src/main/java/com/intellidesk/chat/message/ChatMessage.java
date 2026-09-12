package com.intellidesk.chat.message;

import com.baomidou.mybatisplus.annotation.*;
import com.intellidesk.infrastructure.mybatis.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    private String role;
    private String content;
    private String status;
    private String model;
    private String errorCode;
    private String errorMessage;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String citation;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String tokenUsage;

    private Integer sequenceNo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}