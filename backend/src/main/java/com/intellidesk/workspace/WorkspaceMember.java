package com.intellidesk.workspace;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workspace_member")
public class WorkspaceMember {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workspaceId;
    private Long userId;
    private String role;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime joinedAt;
}