-- ============================================
-- IntelliDesk V2: Knowledge Base + Document Ingestion
-- ============================================

-- 知识库表
CREATE TABLE knowledge_base (
    id BIGSERIAL,
    workspace_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    chunk_strategy VARCHAR(32) NOT NULL DEFAULT 'RECURSIVE',
    chunk_size INTEGER NOT NULL DEFAULT 1000,
    chunk_overlap INTEGER NOT NULL DEFAULT 150,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_knowledge_base PRIMARY KEY (id),
    CONSTRAINT fk_kb_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    CONSTRAINT fk_kb_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE RESTRICT,
    CONSTRAINT chk_kb_chunk_strategy CHECK (chunk_strategy IN ('FIXED_SIZE', 'RECURSIVE')),
    CONSTRAINT chk_kb_chunk_size CHECK (chunk_size BETWEEN 100 AND 4000),
    CONSTRAINT chk_kb_chunk_overlap CHECK (chunk_overlap BETWEEN 0 AND 1000),
    CONSTRAINT chk_kb_chunk_overlap_lt_size CHECK (chunk_overlap < chunk_size),
    CONSTRAINT chk_kb_status CHECK (status IN ('ACTIVE', 'DELETING'))
);

CREATE UNIQUE INDEX uk_kb_workspace_name_ci ON knowledge_base (workspace_id, lower(name));
CREATE INDEX idx_kb_workspace_created ON knowledge_base (workspace_id, created_at DESC, id DESC);
CREATE INDEX idx_kb_workspace_status ON knowledge_base (workspace_id, status);

-- 文档表
CREATE TABLE document (
    id BIGSERIAL,
    knowledge_base_id BIGINT NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    file_extension VARCHAR(16) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    bucket_name VARCHAR(63) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    status VARCHAR(24) NOT NULL,
    chunk_strategy VARCHAR(32) NOT NULL,
    chunk_size INTEGER NOT NULL,
    chunk_overlap INTEGER NOT NULL,
    parser_metadata JSONB NOT NULL DEFAULT '{}',
    failure_code VARCHAR(64),
    failure_message VARCHAR(512),
    cleanup_reason VARCHAR(32),
    cleanup_eligible_at TIMESTAMP,
    created_by BIGINT NOT NULL,
    completed_at TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_document PRIMARY KEY (id),
    CONSTRAINT fk_document_kb FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id) ON DELETE RESTRICT,
    CONSTRAINT fk_document_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE RESTRICT,
    CONSTRAINT chk_document_file_size CHECK (file_size > 0),
    CONSTRAINT chk_document_chunk_size CHECK (chunk_size BETWEEN 100 AND 4000),
    CONSTRAINT chk_document_chunk_overlap CHECK (chunk_overlap BETWEEN 0 AND 1000),
    CONSTRAINT chk_document_chunk_overlap_lt_size CHECK (chunk_overlap < chunk_size),
    CONSTRAINT chk_document_cleanup_reason CHECK (cleanup_reason IN ('USER_DELETE', 'UPLOAD_COMPENSATION')),
    CONSTRAINT chk_document_deleting_cleanup CHECK (
        (status = 'DELETING' AND cleanup_reason IS NOT NULL AND cleanup_eligible_at IS NOT NULL)
        OR (status <> 'DELETING' AND cleanup_reason IS NULL AND cleanup_eligible_at IS NULL)
    ),
    CONSTRAINT uk_document_object_key UNIQUE (object_key),
    CONSTRAINT uk_document_kb_checksum UNIQUE (knowledge_base_id, checksum_sha256),
    CONSTRAINT uk_document_id_kb UNIQUE (id, knowledge_base_id)
);

CREATE INDEX idx_document_kb_created ON document (knowledge_base_id, created_at DESC, id DESC);
CREATE INDEX idx_document_kb_status ON document (knowledge_base_id, status);

-- 文档 Chunk 表
CREATE TABLE document_chunk (
    id BIGSERIAL,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    character_count INTEGER NOT NULL,
    token_count INTEGER,
    page_start INTEGER,
    page_end INTEGER,
    section_path VARCHAR(512),
    start_offset INTEGER,
    end_offset INTEGER,
    source_metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_document_chunk PRIMARY KEY (id),
    CONSTRAINT fk_chunk_document_scope FOREIGN KEY (document_id, knowledge_base_id) REFERENCES document(id, knowledge_base_id) ON DELETE CASCADE,
    CONSTRAINT chk_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT chk_chunk_content CHECK (length(content) > 0),
    CONSTRAINT chk_chunk_character_count CHECK (character_count > 0),
    CONSTRAINT chk_chunk_page_start CHECK (page_start >= 1),
    CONSTRAINT chk_chunk_page_end CHECK (page_end >= page_start),
    CONSTRAINT uk_chunk_document_index UNIQUE (document_id, chunk_index)
);

CREATE INDEX idx_chunk_kb_document_position ON document_chunk (knowledge_base_id, document_id, chunk_index);

-- 文档索引任务表
CREATE TABLE document_index_task (
    id BIGSERIAL,
    document_id BIGINT NOT NULL,
    task_type VARCHAR(32) NOT NULL DEFAULT 'PARSE_AND_CHUNK',
    status VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    message_id UUID NOT NULL,
    next_retry_at TIMESTAMP,
    last_dispatched_at TIMESTAMP,
    lease_until TIMESTAMP,
    last_error_code VARCHAR(64),
    last_error_message VARCHAR(512),
    dead_lettered_at TIMESTAMP,
    requested_by BIGINT NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_document_index_task PRIMARY KEY (id),
    CONSTRAINT fk_task_document FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE,
    CONSTRAINT fk_task_requested_by FOREIGN KEY (requested_by) REFERENCES sys_user(id) ON DELETE RESTRICT,
    CONSTRAINT chk_task_type CHECK (task_type IN ('PARSE_AND_CHUNK')),
    CONSTRAINT chk_task_status CHECK (status IN ('PENDING', 'QUEUED', 'PROCESSING', 'RETRY_WAIT', 'SUCCEEDED', 'DEAD', 'CANCELLED')),
    CONSTRAINT chk_task_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_task_max_attempts CHECK (max_attempts BETWEEN 1 AND 10),
    CONSTRAINT uk_document_task_message UNIQUE (message_id)
);

CREATE UNIQUE INDEX uk_document_active_task ON document_index_task (document_id)
    WHERE status IN ('PENDING', 'QUEUED', 'PROCESSING', 'RETRY_WAIT');
CREATE INDEX idx_task_dispatch ON document_index_task (status, next_retry_at, created_at);
CREATE INDEX idx_task_document_created ON document_index_task (document_id, created_at DESC);

-- Phase 2 权限种子数据
INSERT INTO sys_permission (name, code, description) VALUES
('知识库查看', 'knowledge:view', '查看知识库'),
('知识库管理', 'knowledge:manage', '创建、修改、删除知识库'),
('文档查看', 'document:view', '查看文档和 Chunk'),
('文档管理', 'document:manage', '上传、删除、重试文档');

-- 为 ADMIN 和 MEMBER 角色分配全部 Phase 2 权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code IN ('ADMIN', 'MEMBER')
  AND p.code IN ('knowledge:view', 'knowledge:manage', 'document:view', 'document:manage');
