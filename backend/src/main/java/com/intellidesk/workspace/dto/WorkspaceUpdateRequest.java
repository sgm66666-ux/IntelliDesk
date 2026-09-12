package com.intellidesk.workspace.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WorkspaceUpdateRequest {
    @Size(max = 128, message = "工作空间名称最大 128 字符")
    private String name;

    @Size(max = 512, message = "描述最大 512 字符")
    private String description;
}