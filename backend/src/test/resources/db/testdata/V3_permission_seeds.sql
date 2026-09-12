-- Phase 2: Knowledge / Document permission seeds for test environment
-- These permissions are normally seeded in V2__knowledge_document_ingestion.sql (Flyway),
-- but in test profile we use H2 and only load V1 schema.

INSERT INTO sys_permission (name, code, description)
SELECT '知识库查看', 'knowledge:view', '查看知识库'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'knowledge:view');

INSERT INTO sys_permission (name, code, description)
SELECT '知识库管理', 'knowledge:manage', '创建、修改、删除知识库'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'knowledge:manage');

INSERT INTO sys_permission (name, code, description)
SELECT '文档查看', 'document:view', '查看文档和 Chunk'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'document:view');

INSERT INTO sys_permission (name, code, description)
SELECT '文档管理', 'document:manage', '上传、删除、重试文档'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'document:manage');

-- Assign to MEMBER role
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'MEMBER'
  AND p.code IN ('knowledge:view', 'knowledge:manage', 'document:view', 'document:manage')
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_permission rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- Phase 3 Wave 3: Retrieval permission seeds for test environment
-- These permissions are normally seeded in V3__rag_retrieval.sql (Flyway),
-- but in test profile we use H2 and only load V1 schema.

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

-- Phase 4: Conversation permission seeds
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