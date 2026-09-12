-- ============================================
-- IntelliDesk V3: RAG Retrieval
-- ============================================

-- 1. Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. Add embedding columns to document_chunk
ALTER TABLE document_chunk
    ADD COLUMN embedding vector(1536),
    ADD COLUMN embedding_model VARCHAR(128),
    ADD COLUMN embedding_generation INTEGER,
    ADD COLUMN embedding_fence_token BIGINT,
    ADD COLUMN embedded_at TIMESTAMP;

-- 3. Embedding metadata consistency check
ALTER TABLE document_chunk
    ADD CONSTRAINT chk_chunk_embedding_metadata CHECK (
        (embedding IS NULL
         AND embedding_model IS NULL
         AND embedding_generation IS NULL
         AND embedding_fence_token IS NULL
         AND embedded_at IS NULL)
        OR
        (embedding IS NOT NULL
         AND embedding_model IS NOT NULL
         AND embedding_generation > 0
         AND embedding_fence_token > 0
         AND embedded_at IS NOT NULL)
    );

-- 4. HNSW index for cosine similarity search
CREATE INDEX idx_chunk_embedding_hnsw
    ON document_chunk USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;

-- 5. Document retrieval task table
-- One current durable indexing record per Document.
-- Separate from Phase 2 document_index_task to avoid message routing conflicts.
CREATE TABLE document_retrieval_task (
    id BIGSERIAL,
    document_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    generation INTEGER NOT NULL DEFAULT 1,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    fence_token BIGINT NOT NULL DEFAULT 0,
    message_id UUID NOT NULL,
    embedding_model VARCHAR(128),
    embedding_dimension INTEGER NOT NULL DEFAULT 1536,
    es_index_name VARCHAR(128) NOT NULL DEFAULT 'intellidesk-chunks-v1',
    indexed_chunk_count INTEGER,
    next_retry_at TIMESTAMP,
    last_dispatched_at TIMESTAMP,
    lease_until TIMESTAMP,
    last_error_code VARCHAR(64),
    last_error_message VARCHAR(512),
    requested_by BIGINT,
    started_at TIMESTAMP,
    ready_at TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_document_retrieval_task PRIMARY KEY (id),
    CONSTRAINT fk_retrieval_task_document FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE,
    CONSTRAINT uk_retrieval_task_document UNIQUE (document_id),
    CONSTRAINT chk_retrieval_task_status CHECK (status IN ('PENDING', 'QUEUED', 'PROCESSING', 'RETRY_WAIT', 'READY', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_retrieval_task_generation CHECK (generation > 0),
    CONSTRAINT chk_retrieval_task_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_retrieval_task_max_attempts CHECK (max_attempts BETWEEN 1 AND 10),
    CONSTRAINT chk_retrieval_task_fence_token CHECK (fence_token >= 0),
    CONSTRAINT chk_retrieval_task_dimension CHECK (embedding_dimension = 1536),
    CONSTRAINT chk_retrieval_task_indexed_count CHECK (indexed_chunk_count IS NULL OR indexed_chunk_count >= 0),
    CONSTRAINT chk_retrieval_task_ready CHECK (
        (status = 'READY' AND embedding_model IS NOT NULL AND embedding_dimension = 1536 AND indexed_chunk_count IS NOT NULL AND indexed_chunk_count >= 0 AND ready_at IS NOT NULL)
        OR (status <> 'READY')
    )
);

-- Indexes for dispatcher and recovery
CREATE INDEX idx_retrieval_task_dispatch ON document_retrieval_task (status, next_retry_at, created_at);
CREATE INDEX idx_retrieval_task_lease ON document_retrieval_task (status, lease_until);

-- 6. Retrieval cleanup task table
-- Durable deletion record for Elasticsearch cleanup.
-- No FK to document because the record must survive hard deletion.
CREATE TABLE retrieval_cleanup_task (
    id BIGSERIAL,
    document_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    es_index_name VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    not_before TIMESTAMP NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_retry_at TIMESTAMP,
    lease_until TIMESTAMP,
    last_error_code VARCHAR(64),
    last_error_message VARCHAR(512),
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_retrieval_cleanup_task PRIMARY KEY (id),
    CONSTRAINT uk_cleanup_task_document UNIQUE (document_id),
    CONSTRAINT chk_cleanup_task_status CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'SUCCEEDED', 'DEAD')),
    CONSTRAINT chk_cleanup_task_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_cleanup_task_max_attempts CHECK (max_attempts BETWEEN 1 AND 10)
);

CREATE INDEX idx_cleanup_task_dispatch ON retrieval_cleanup_task (status, next_retry_at, created_at);
CREATE INDEX idx_cleanup_task_lease ON retrieval_cleanup_task (status, lease_until);

-- 7. Backfill: create PENDING retrieval tasks for existing COMPLETED documents with chunks
INSERT INTO document_retrieval_task (document_id, status, generation, attempt_count, max_attempts, message_id, embedding_dimension, es_index_name)
SELECT d.id, 'PENDING', 1, 0, 3, gen_random_uuid(), 1536, 'intellidesk-chunks-v1'
FROM document d
WHERE d.status = 'COMPLETED'
  AND EXISTS (SELECT 1 FROM document_chunk c WHERE c.document_id = d.id)
  AND NOT EXISTS (SELECT 1 FROM document_retrieval_task rt WHERE rt.document_id = d.id);

-- 8. Phase 3 permission seeds
INSERT INTO sys_permission (name, code, description)
SELECT '检索查看', 'retrieval:view', '使用检索功能'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'retrieval:view');

INSERT INTO sys_permission (name, code, description)
SELECT '检索管理', 'retrieval:manage', '管理检索索引和重索引'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'retrieval:manage');

-- Assign to ADMIN and MEMBER roles
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code IN ('ADMIN', 'MEMBER')
  AND p.code IN ('retrieval:view', 'retrieval:manage')
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_permission rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );