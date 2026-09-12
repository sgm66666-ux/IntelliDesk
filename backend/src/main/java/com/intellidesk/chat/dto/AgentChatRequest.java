package com.intellidesk.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentChatRequest {

    @NotBlank(message = "query must not be blank")
    @Size(max = 5000, message = "query must not exceed 5000 characters")
    private String query;
}