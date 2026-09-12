package com.intellidesk.workspace.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AddMemberRequest {
    @NotNull(message = "用户ID不能为空")
    private Long userId;
}