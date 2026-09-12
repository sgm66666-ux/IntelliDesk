package com.intellidesk.chat.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ConversationCreateRequest {

    @Size(max = 256, message = "标题不能超过 256 个字符")
    private String title;
}