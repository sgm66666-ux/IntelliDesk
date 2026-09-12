-- ============================================
-- IntelliDesk V1: Phase 1 基础表结构
-- ============================================

-- 用户表
CREATE TABLE sys_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(256) NOT NULL,
    email VARCHAR(128),
    nickname VARCHAR(64),
    avatar_url VARCHAR(512),
    status SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);

-- 角色表
CREATE TABLE sys_role (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    code VARCHAR(64) NOT NULL,
    description VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_role_name UNIQUE (name),
    CONSTRAINT uk_sys_role_code UNIQUE (code)
);

-- 权限表
CREATE TABLE sys_permission (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    code VARCHAR(128) NOT NULL,
    description VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_permission_code UNIQUE (code)
);

-- 用户角色关联表
CREATE TABLE sys_user_role (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    CONSTRAINT fk_sys_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_sys_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
    CONSTRAINT uk_sys_user_role UNIQUE (user_id, role_id)
);

-- 角色权限关联表
CREATE TABLE sys_role_permission (
    id BIGSERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    CONSTRAINT fk_sys_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
    CONSTRAINT fk_sys_role_permission_perm FOREIGN KEY (permission_id) REFERENCES sys_permission(id),
    CONSTRAINT uk_sys_role_permission UNIQUE (role_id, permission_id)
);

-- 工作空间表
CREATE TABLE workspace (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    owner_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_workspace_owner FOREIGN KEY (owner_id) REFERENCES sys_user(id)
);

-- 工作空间成员表
CREATE TABLE workspace_member (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_workspace_member_ws FOREIGN KEY (workspace_id) REFERENCES workspace(id),
    CONSTRAINT fk_workspace_member_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT uk_workspace_member UNIQUE (workspace_id, user_id)
);

-- 索引
CREATE INDEX idx_sys_user_role_user_id ON sys_user_role(user_id);
CREATE INDEX idx_sys_user_role_role_id ON sys_user_role(role_id);
CREATE INDEX idx_sys_role_permission_role_id ON sys_role_permission(role_id);
CREATE INDEX idx_workspace_owner_id ON workspace(owner_id);
CREATE INDEX idx_workspace_member_workspace_id ON workspace_member(workspace_id);
CREATE INDEX idx_workspace_member_user_id ON workspace_member(user_id);

-- 初始化角色数据
INSERT INTO sys_role (name, code, description) VALUES
('管理员', 'ADMIN', '系统管理员'),
('成员', 'MEMBER', '普通成员');

-- 初始化权限数据
INSERT INTO sys_permission (name, code, description) VALUES
('工作空间管理', 'workspace:manage', '创建、修改、删除工作空间'),
('工作空间查看', 'workspace:view', '查看工作空间'),
('工作空间创建', 'workspace:create', '创建工作空间'),
('成员管理', 'member:manage', '管理工作空间成员'),
('用户管理', 'user:manage', '管理用户');

-- 为 ADMIN 角色分配所有权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'ADMIN';

-- 为 MEMBER 角色分配基本权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'MEMBER' AND p.code IN ('workspace:view', 'workspace:create');