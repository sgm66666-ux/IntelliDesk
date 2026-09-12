package com.intellidesk.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ConversationUpdateRequest {

    @NotBlank(message = "标题不能为空")
    @Size(min = 1, max = 256, message = "标题长度为 1-256 个字符")
    private String title;
}