# IntelliDesk 架构设计文档

> 本文下方保留早期 Phase 0 设计草案（包含当时的候选版本、规划模块和概念 ER），不能把每一项规划视为当前已实现功能。当前实施依据为源码、迁移与 [Java 后端增强报告](backend/JAVA_BACKEND_ENHANCEMENT_REPORT.md)，实际依赖版本以 backend/pom.xml、frontend/package-lock.json 和 deploy/docker-compose.yml 为准。

## 当前实现概要（2026-10-05）

IntelliDesk 是 Java / Spring Boot 模块化单体，Vue 经 Nginx 调用后端。Spring Security/JWT、数据库 RBAC 与 workspace membership 共同授权；API Key 另受 scope 限制。业务持久化使用 PostgreSQL 与 MyBatis-Plus，不是 JPA/MySQL。

文档上传至 MinIO 后创建持久化 ingestion task，经 RabbitMQ 或 dispatcher 执行。短事务协调状态 claim、Chunk 保存和后续 retrieval task 创建；解析与外部 I/O 位于事务外。检索任务独立完成 Embedding、pgvector 与 Elasticsearch 索引，因此 ingestion COMPLETED 不等于检索 READY。

重试使用既有 TTL 队列、数据库 deadline 和 DLQ。成功提交后的重复消息可跳过处理；提交前故障可能再次解析。status/attempt CAS、document version CAS、唯一约束和事务保护提交边界，不承诺 exactly-once。

Redis 新增部分仅为授权后的 KB metadata Cache Aside：TTL 300–360 秒、DB 提交后失效、故障回退 DB；不缓存最终权限。TraceId 关联 HTTP、持久化 task 与 MQ，消费结束恢复 MDC。V6 保存 task trace，V7 增加 status/lease_until 索引；EXPLAIN 依据见 [数据库分析](backend/DATABASE_ACCESS_REVIEW.md)。现有 RAG / Agent 流程没有因本轮 Java 增强而改变。

以下历史设计草案不是新的交付范围或测试结论。

---

## 一、技术选型与版本

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 21 LTS | 长期支持，虚拟线程 |
| Spring Boot | 3.5.8 | BOM 管理依赖 |
| Spring AI | 1.1.2 | 统一 AI 模型接入 |
| Spring AI Alibaba | 1.1.2.2 | 阿里云 AI 服务集成 |
| MyBatis-Plus | 3.5.x | 增强 MyBatis |
| PostgreSQL | 16 | 关系型数据库 |
| pgvector | 0.7.x | PostgreSQL 向量扩展 |
| Redis | 7.2 | 缓存 + 分布式锁 + 限流 |
| Elasticsearch | 8.x | 全文检索 + BM25 |
| RabbitMQ | 4.x | 异步文档索引 |
| MinIO | latest | S3 兼容对象存储 |
| Flyway | 10.x | 数据库迁移 |
| Maven | 3.9+ | 构建工具 |
| Vue 3 | 3.5+ | 前端框架 |
| Vite | 5.x | 前端构建 |
| Element Plus | 2.x | UI 组件库 |
| Docker | 26+ | 容器化 |
| Testcontainers | 1.20+ | 集成测试 |
| k6 | latest | 性能测试 |

> 以上版本基于 Spring AI Alibaba 官方稳定兼容关系确定，Phase 1 初始化时使用 BOM 统一管理。

---

## 二、系统架构

采用**模块化单体**架构，backend 内部按业务域分包。

```
┌─────────────────────────────────────────────────────┐
│                    Nginx (后期)                       │
├──────────────────────┬──────────────────────────────┤
│    Vue 3 Frontend    │    Spring Boot Backend        │
│    (Vite + Element)  │                                │
│                      │  ┌──────┐ ┌──────┐ ┌──────┐  │
│                      │  │ Auth │ │ User │ │Worksp│  │
│                      │  └──────┘ └──────┘ └──────┘  │
│                      │  ┌──────┐ ┌──────┐ ┌──────┐  │
│                      │  │Knowle│ │ Doc  │ │RAG   │  │
│                      │  └──────┘ └──────┘ └──────┘  │
│                      │  ┌──────┐ ┌──────┐ ┌──────┐  │
│                      │  │ Chat │ │Agent │ │Tool  │  │
│                      │  └──────┘ └──────┘ └──────┘  │
│                      │  ┌──────┐ ┌──────┐ ┌──────┐  │
│                      │  │Ticket│ │ LLM  │ │Usage │  │
│                      │  └──────┘ └──────┘ └──────┘  │
│                      │  ┌────────────────────────┐  │
│                      │  │   Infrastructure       │  │
│                      │  └────────────────────────┘  │
├──────────────────────┴──────────────────────────────┤
│                   Infrastructure                      │
│  ┌──────────┐ ┌───────┐ ┌──────────┐ ┌──────────┐  │
│  │PostgreSQL│ │ Redis │ │   MinIO  │ │ RabbitMQ │  │
│  │+pgvector │ │       │ │          │ │          │  │
│  └──────────┘ └───────┘ └──────────┘ └──────────┘  │
│  ┌──────────────┐                                    │
│  │Elasticsearch │                                    │
│  └──────────────┘                                    │
└─────────────────────────────────────────────────────┘
```

---

## 三、Backend Module 划分

```
backend/
└── src/main/java/com/intellidesk/
    ├── common/           # 通用: Result, ErrorCode, Exception, TraceId
    ├── auth/             # 认证: JWT, RefreshToken, Logout
    ├── user/             # 用户: 注册, 登录, RBAC
    ├── workspace/        # 工作空间: CRUD, 成员管理
    ├── knowledge/        # 知识库: KnowledgeBase CRUD
    ├── document/         # 文档: 上传, 解析, Chunk, IndexTask
    ├── retrieval/        # 检索: VectorRetriever, KeywordRetriever, Hybrid
    ├── rag/              # RAG: QueryRewrite, ContextBuilder, Citation
    ├── chat/             # 对话: Conversation, Message, SSE
    ├── agent/            # 智能体: Agent CRUD, AgentLoop
    ├── tool/             # 工具: ToolRegistry, ToolExecutor
    ├── ticket/           # 工单: Ticket CRUD
    ├── llm/              # LLM: 模型抽象, Usage 记录
    ├── usage/            # 用量: Token 统计, Dashboard
    └── infrastructure/   # 基础设施: Redis, MQ, MinIO, ES 配置
```

---

## 四、ER 设计

### 用户与权限

```mermaid
erDiagram
    sys_user ||--o{ sys_user_role : has
    sys_role ||--o{ sys_user_role : has
    sys_role ||--o{ sys_role_permission : has
    sys_permission ||--o{ sys_role_permission : has
    sys_user ||--o{ workspace_member : belongs
    workspace ||--o{ workspace_member : has

    sys_user {
        bigint id PK
        varchar username UK
        varchar password_hash
        varchar email
        varchar nickname
        varchar avatar_url
        tinyint status
        timestamp created_at
        timestamp updated_at
    }

    sys_role {
        bigint id PK
        varchar name UK
        varchar code UK
        varchar description
        timestamp created_at
    }

    sys_permission {
        bigint id PK
        varchar name
        varchar code UK
        varchar description
        timestamp created_at
    }

    sys_user_role {
        bigint id PK
        bigint user_id FK
        bigint role_id FK
    }

    sys_role_permission {
        bigint id PK
        bigint role_id FK
        bigint permission_id FK
    }

    workspace {
        bigint id PK
        varchar name
        varchar description
        bigint owner_id FK
        timestamp created_at
        timestamp updated_at
    }

    workspace_member {
        bigint id PK
        bigint workspace_id FK
        bigint user_id FK
        varchar role
        timestamp joined_at
    }
```

### 知识库与文档

```mermaid
erDiagram
    workspace ||--o{ knowledge_base : contains
    knowledge_base ||--o{ document : contains
    document ||--o{ document_chunk : has
    document ||--o{ document_index_task : has
    knowledge_base ||--o{ agent_knowledge_base : linked

    knowledge_base {
        bigint id PK
        bigint workspace_id FK
        varchar name
        varchar description
        varchar embedding_model
        int chunk_size
        int chunk_overlap
        varchar chunk_strategy
        timestamp created_at
        timestamp updated_at
    }

    document {
        bigint id PK
        bigint knowledge_base_id FK
        varchar file_name
        varchar file_type
        bigint file_size
        varchar content_type
        varchar object_key
        varchar checksum
        varchar status
        text error_message
        timestamp created_at
        timestamp updated_at
    }

    document_chunk {
        bigint id PK
        bigint knowledge_base_id FK
        bigint document_id FK
        text content
        int token_count
        int position
        jsonb metadata
        vector embedding
        timestamp created_at
    }

    document_index_task {
        bigint id PK
        bigint document_id FK
        varchar status
        text error_message
        int retry_count
        timestamp started_at
        timestamp completed_at
        timestamp created_at
    }
```

### 对话与 Agent

```mermaid
erDiagram
    workspace ||--o{ conversation : has
    conversation ||--o{ chat_message : contains
    workspace ||--o{ agent : has
    agent ||--o{ agent_knowledge_base : uses
    agent ||--o{ agent_tool : uses
    agent ||--o{ tool_trace : records

    conversation {
        bigint id PK
        bigint workspace_id FK
        bigint user_id FK
        varchar title
        varchar model
        bigint agent_id FK
        timestamp created_at
        timestamp updated_at
    }

    chat_message {
        bigint id PK
        bigint conversation_id FK
        varchar role
        text content
        jsonb citations
        jsonb metadata
        timestamp created_at
    }

    agent {
        bigint id PK
        bigint workspace_id FK
        varchar name
        text description
        text system_prompt
        varchar model
        double temperature
        int max_steps
        boolean enabled
        timestamp created_at
        timestamp updated_at
    }

    agent_knowledge_base {
        bigint id PK
        bigint agent_id FK
        bigint knowledge_base_id FK
    }

    agent_tool {
        bigint id PK
        bigint agent_id FK
        varchar tool_name
        jsonb config
    }

    tool_trace {
        bigint id PK
        bigint conversation_id FK
        bigint agent_id FK
        varchar tool_name
        jsonb arguments
        text result_summary
        varchar status
        bigint duration_ms
        timestamp created_at
    }
```

### API Key 与用量

```mermaid
erDiagram
    workspace ||--o{ api_key : has
    sys_user ||--o{ llm_usage : has

    api_key {
        bigint id PK
        bigint workspace_id FK
        varchar name
        varchar key_prefix
        varchar key_hash
        varchar status
        timestamp expires_at
        timestamp created_at
    }

    llm_usage {
        bigint id PK
        bigint user_id FK
        bigint agent_id FK
        bigint conversation_id FK
        varchar model
        int prompt_tokens
        int completion_tokens
        int total_tokens
        bigint first_token_latency_ms
        bigint total_latency_ms
        timestamp created_at
    }
```

### 工单

```mermaid
erDiagram
    workspace ||--o{ ticket : has

    ticket {
        bigint id PK
        bigint workspace_id FK
        bigint user_id FK
        varchar title
        text description
        varchar status
        varchar priority
        bigint assigned_to FK
        timestamp created_at
        timestamp updated_at
        timestamp resolved_at
    }
```

---

## 五、API 设计

### 统一响应格式

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "traceId": "uuid"
}
```

### Phase 1 API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/auth/register | 注册 |
| POST | /api/auth/login | 登录 |
| POST | /api/auth/refresh | 刷新 Token |
| POST | /api/auth/logout | 登出 |
| GET | /api/users/me | 当前用户信息 |
| PUT | /api/users/me | 更新个人信息 |
| POST | /api/workspaces | 创建工作空间 |
| GET | /api/workspaces | 工作空间列表 |
| GET | /api/workspaces/{id} | 工作空间详情 |
| PUT | /api/workspaces/{id} | 更新工作空间 |
| DELETE | /api/workspaces/{id} | 删除工作空间 |
| POST | /api/workspaces/{id}/members | 添加成员 |
| DELETE | /api/workspaces/{id}/members/{userId} | 移除成员 |

### 后续阶段 API 端点（规划）

| 方法 | 路径 | 说明 | Phase |
|------|------|------|-------|
| POST | /api/knowledge-bases | 创建知识库 | 2 |
| GET | /api/knowledge-bases | 知识库列表 | 2 |
| POST | /api/knowledge-bases/{id}/documents | 上传文档 | 2 |
| GET | /api/retrieval/search | 混合检索 | 3 |
| POST | /api/chat/stream | 流式对话 | 4 |
| POST | /api/agents | 创建 Agent | 5 |
| POST | /v1/chat/completions | OpenAI-compatible API | 6 |

---

## 六、RAG Pipeline 设计

```
┌──────────────────────────────────────────────────────────┐
│                      RAG Pipeline                         │
│                                                           │
│  User Query                                               │
│      │                                                    │
│      ▼                                                    │
│  ┌──────────────┐                                         │
│  │ Query Rewrite │  ← 多轮对话上下文改写                    │
│  └──────┬───────┘     (可配置关闭)                         │
│         │                                                  │
│    ┌────┴────┐                                            │
│    ▼         ▼                                            │
│  ┌─────┐  ┌──────┐                                       │
│  │Vector│  │ BM25 │                                       │
│  │pgvec │  │  ES  │                                       │
│  └──┬──┘  └──┬───┘                                       │
│     │        │                                            │
│     └───┬────┘                                            │
│         ▼                                                 │
│  ┌──────────┐                                             │
│  │ RRF Fusion│  ← k=60 可配置                             │
│  └─────┬────┘                                             │
│        │                                                  │
│        ▼                                                  │
│  ┌──────────┐                                             │
│  │ Reranker │  ← Top 30 → Top 5~8                        │
│  └─────┬────┘                                             │
│        │                                                  │
│        ▼                                                  │
│  ┌────────────────┐                                       │
│  │ Context Builder │  ← Token Budget, 去重, Citation ID   │
│  └───────┬────────┘                                       │
│          │                                                │
│          ▼                                                │
│  ┌──────────┐                                             │
│  │   LLM    │  ← Prompt Template + Context + History      │
│  └─────┬────┘                                             │
│        │                                                  │
│        ▼                                                  │
│  ┌──────────┐                                             │
│  │ Citation │  ← documentId, chunkId, content, score      │
│  └─────┬────┘                                             │
│        │                                                  │
│        ▼                                                  │
│  ┌──────────┐                                             │
│  │   SSE    │  ← start → token → citation → done          │
│  └──────────┘                                             │
└──────────────────────────────────────────────────────────┘
```

### 数据流

```
文档上传 → MinIO → MQ → Parser → ChunkStrategy → EmbeddingService
                                                    ├── pgvector (向量)
                                                    └── Elasticsearch (BM25)
```

### 检索器接口

```java
public interface Retriever {
    List<RetrievalResult> retrieve(RetrievalQuery query);
}
```

实现：
- `VectorRetriever` — pgvector 向量检索
- `KeywordRetriever` — Elasticsearch BM25 检索
- `HybridRetriever` — RRF 融合

---

## 七、Agent Pipeline 设计

```
┌──────────────────────────────────────────────────────┐
│                    Agent Loop                         │
│                                                       │
│  User Message                                         │
│      │                                                │
│      ▼                                                │
│  ┌──────────┐                                         │
│  │  Agent   │  ← System Prompt + Tools + History      │
│  └────┬─────┘                                         │
│       │                                               │
│       ▼                                               │
│  ┌──────────┐    Yes    ┌──────────────┐             │
│  │ Need Tool?│─────────►│ Tool Registry │             │
│  └────┬─────┘           │ Tool Executor │             │
│       │ No              │ Tool Trace    │             │
│       │                 └──────┬───────┘             │
│       │                        │                      │
│       │                  Observation                  │
│       │                        │                      │
│       │◄───────────────────────┘                      │
│       │                                               │
│       ▼                                               │
│  ┌──────────┐                                         │
│  │  Answer  │  ← maxSteps / maxToolCalls / timeout    │
│  └──────────┘                                         │
└──────────────────────────────────────────────────────┘
```

### Tool Framework

```java
public interface AgentTool {
    String getName();
    String getDescription();
    JsonSchema getParametersSchema();
    boolean requiresConfirmation();
    ToolResult execute(ToolContext context);
}
```

第一版 Tool 列表：
- `searchKnowledge` — 知识库检索
- `getOrder` — 查询订单 (Mock)
- `getInventory` — 查询库存 (Mock)
- `getLogistics` — 查询物流 (Mock)
- `createTicket` — 创建工单
- `refundOrder` — 退款 (requiresConfirmation=true)

### 安全限制

- `maxSteps` — 最大推理步数
- `maxToolCalls` — 最大工具调用次数
- `timeout` — 总超时
- `requiresConfirmation` — 危险操作确认
- 工具参数校验

---

## 八、安全设计

| 层面 | 方案 |
|------|------|
| 认证 | JWT + Refresh Token (Redis) |
| 授权 | RBAC (ADMIN / MEMBER) |
| 密码 | BCrypt 哈希 |
| API Key | 唯一 prefix 定位候选 + BCrypt(secret) 校验；完整 key 仅创建时返回一次 |
| 限流 | Redis + Lua (用户级 / API Key 级) |
| 敏感信息 | .env 管理，不入 Git |
| CORS | Spring Security 配置 |
| Trace ID | 全链路追踪 |

---

## 九、Docker 架构

```
deploy/
├── docker-compose.yml
├── .env.example
├── postgres/
│   └── init.sql          # pgvector 扩展初始化
├── redis/
│   └── redis.conf
├── rabbitmq/
│   └── rabbitmq.conf
├── elasticsearch/
│   └── elasticsearch.yml
└── minio/
```

### 基础设施服务

```yaml
services:
  postgres:     # 5432
  redis:        # 6379
  rabbitmq:     # 5672, 15672 (management)
  elasticsearch:# 9200
  minio:        # 9000, 9001 (console)
```

---

## 十、开发路线图 Phase 1 实施计划

Phase 1 目标：完成基础后端，可运行、可测试、可验证。

### 步骤

1. **初始化 Maven 项目**
   - 创建 Spring Boot 项目骨架
   - 配置 pom.xml (Spring Boot, MyBatis-Plus, Flyway, PostgreSQL, Redis)
   - 配置 application.yml

2. **数据库 Migration (Flyway)**
   - V1__init_schema.sql — sys_user, sys_role, sys_permission, workspace 等表

3. **Common 模块**
   - Result<T> 统一响应
   - ErrorCode 枚举
   - GlobalExceptionHandler
   - TraceId 过滤器

4. **Auth 模块**
   - JWT 生成与验证
   - RefreshToken (Redis 存储)
   - Login/Logout/Refresh API

5. **User 模块**
   - Register API
   - 用户信息 API
   - RBAC 数据初始化

6. **Workspace 模块**
   - CRUD API
   - 成员管理

7. **测试**
   - Auth 单元测试
   - User 集成测试
   - Workspace 集成测试

8. **Docker 基础设施**
   - docker-compose.yml
   - 各服务配置

### 验收标准

```bash
# 编译通过
mvn clean compile

# 测试通过
mvn clean test

# 启动成功
mvn spring-boot:run

# API 验证
curl -X POST http://localhost:8080/api/auth/register
curl -X POST http://localhost:8080/api/auth/login
curl -X POST http://localhost:8080/api/auth/refresh
curl -X POST http://localhost:8080/api/workspaces
```
