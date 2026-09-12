package com.intellidesk.api_key;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("api_key")
public class ApiKey {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workspaceId;
    private Long userId;
    private String name;
    private String keyPrefix;
    private String keyHash;
    private String scope;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime revokedAt;
}