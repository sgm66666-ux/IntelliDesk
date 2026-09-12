-- ============================================
-- IntelliDesk V4: Chat Conversation
-- ============================================

-- 1. Conversation table
CREATE TABLE conversation (
    id BIGSERIAL,
    workspace_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(256) NOT NULL DEFAULT 'New Conversation',
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_conversation PRIMARY KEY (id),
    CONSTRAINT fk_conversation_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
    CONSTRAINT chk_conversation_status CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED'))
);

CREATE INDEX idx_conversation_workspace_user ON conversation (workspace_id, user_id, status, updated_at DESC);
CREATE INDEX idx_conversation_workspace ON conversation (workspace_id, status);

-- 2. Chat message table
CREATE TABLE chat_message (
    id BIGSERIAL,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'SUCCESS',
    model VARCHAR(128),
    error_code VARCHAR(64),
    error_message VARCHAR(512),
    citation JSONB,
    token_usage JSONB,
    sequence_no INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_chat_message PRIMARY KEY (id),
    CONSTRAINT fk_chat_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id) ON DELETE CASCADE,
    CONSTRAINT chk_chat_message_role CHECK (role IN ('SYSTEM', 'USER', 'ASSISTANT', 'TOOL')),
    CONSTRAINT chk_chat_message_status CHECK (status IN ('GENERATING', 'SUCCESS', 'FAILED', 'CANCELLED')),
    CONSTRAINT uk_chat_message_conversation_seq UNIQUE (conversation_id, sequence_no)
);

CREATE INDEX idx_chat_message_conversation_seq ON chat_message (conversation_id, sequence_no);
CREATE INDEX idx_chat_message_status ON chat_message (status, created_at);

-- 3. Phase 4 permission seeds
INSERT INTO sys_permission (name, code, description)
SELECT '对话查看', 'conversation:view', '查看和管理自己的对话'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'conversation:view');

INSERT INTO sys_permission (name, code, description)
SELECT '对话管理', 'conversation:manage', '管理对话（删除等）'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'conversation:manage');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code IN ('ADMIN', 'MEMBER')
  AND p.code IN ('conversation:view', 'conversation:manage')
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_permission rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );