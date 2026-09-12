package com.intellidesk.workspace;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workspace")
public class Workspace {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String description;
    private Long ownerId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}