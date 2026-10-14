# IntelliDesk 架构设计文档

IntelliDesk 采用 Java / Spring Boot 模块化单体架构，为企业文档管理、工作空间权限与智能问答提供统一后端。本文描述当前公开源码中的模块和实现机制；具体依赖与运行参数以 `backend/pom.xml`、`frontend/package.json` 和部署配置为准。

## 系统架构

~~~text
Vue 3 / TypeScript → Nginx → Spring Boot
                              ├─ Spring Security / JWT / RBAC / workspace authorization
                              ├─ 文档管理 / 持久化任务 / RabbitMQ 消费与恢复
                              ├─ pgvector / BM25 / RRF / 可选 Reranker
                              ├─ RAG 对话 / SSE / Agent 工具调用
                              └─ 统一异常处理 / TraceId / Actuator
基础设施：PostgreSQL + pgvector、Redis、RabbitMQ、MinIO、Elasticsearch
模型接入：外部聊天与 Embedding Provider
~~~

业务逻辑运行于同一个后端应用，基础设施作为独立服务部署。聊天模型与 Embedding 通过配置接入，模型能力与运行环境需要单独验证。

## 后端模块

`backend/src/main/java/com/intellidesk/` 按职责分包：

| 模块 | 职责 |
| --- | --- |
| `auth`、`user`、`workspace` | 登录、Token、数据库角色权限、工作空间及成员授权 |
| `api_key` | API Key 创建、metadata、scope 与认证 |
| `knowledge` | 知识库管理与小型元数据缓存 |
| `document` | 上传、解析、分块、持久化任务及消息消费 |
| `embedding`、`retrieval` | 向量化、Vector / BM25 / Hybrid 检索、重排与索引任务 |
| `chat` | 对话与消息持久化、Query Rewrite、上下文、引用和 SSE |
| `agent` | 有界工具调用、参数校验、结果脱敏与工具轨迹 |
| `common`、`infrastructure` | 响应、异常、TraceId 与基础设施集成 |

数据库实体和约束以 [Flyway 迁移](../backend/src/main/resources/db/migration/)为准；HTTP 接口以各 Controller 为准。

## 文档与索引数据流

~~~text
授权上传 → MinIO 对象 → 持久化 ingestion task
   → RabbitMQ / dispatcher → 原子 claim PROCESSING
   → 事务外解析 Markdown / TXT / PDF 并分块
   → DB 事务保存 Chunk、SUCCEEDED / COMPLETED 与 retrieval task
   → 独立检索任务生成 Embedding，写入 pgvector / Elasticsearch
   → RetrievalTask READY
~~~

文档解析分块与检索索引是两个独立阶段。文档 `COMPLETED` 和 ingestion task `SUCCEEDED` 表示解析分块完成，检索可用性还需检查 retrieval task 的 `READY` 状态。

短数据库事务保护任务 claim、状态更新和关联数据提交。MinIO、解析计算、模型与 Elasticsearch 调用位于事务之外；外部失败由持久化任务、重试和补偿流程协调。

## 异步任务与一致性

RabbitMQ 使用持久化拓扑、手动 ACK、publisher confirm/return、延迟重试与 DLQ。数据库保存任务身份、尝试次数、租约与状态；status/attempt 条件更新、document version CAS 和唯一约束保护并发提交。

成功提交后的重复投递可跳过处理；提交前故障或租约失效仍可能触发重新解析。数据库提交、外部存储和消息投递之间需要恢复流程协调，不能据此承诺端到端 exactly-once。

## 检索与 RAG

~~~text
用户问题与对话历史
   → 工作空间 / 知识库检索范围校验
   → 可配置 Query Rewrite
   → Vector（pgvector）与 BM25（Elasticsearch）
   → Hybrid 的 RRF 融合 → 可选 Reranker
   → Context Builder / 上下文预算 / Citation
   → 聊天 Provider → SSE 增量回答与引用来源
~~~

检索、重排和生成是不同环节。Reranker 能调整候选顺序，不能补回初始候选池缺失的证据；引用用于来源追踪，不能替代答案正确性验证。

[受控评测](evaluation/report.md)分别报告 chunk-level 与 document-level 的相关性指标。在该合成语料上，HYBRID_RERANK 低于 HYBRID，BM25 的 chunk MRR 高于 Hybrid。结果只适用于对应数据集和实验配置。

## Agent 工具调用

Agent 通过工具注册、执行与轨迹记录完成知识库搜索、知识库列表/详情、文档列表/详情等已有工具操作。执行包含参数与权限校验、工具结果脱敏、工具超时、循环步数和总时长限制。工具调用沿用应用授权体系，LLM 不决定访问权限。

## 安全、缓存与可观测性

Spring Security 校验 JWT，数据库 RBAC 与 workspace member/owner 授权共同约束资源访问；API Key 另受 scope 限制。密码使用 BCrypt，完整 API Key 仅创建时返回一次，后续仅展示 metadata。

Redis 保存 refresh token、限流数据，并用于知识库小型元数据 Cache Aside。元数据每次先验证数据库授权，再查缓存；TTL 为自最后一次写入起 300–360 秒，DB 提交后失效，Redis 故障回退数据库。并发回填和失效延迟可能产生短暂陈旧数据，该缓存采用最终一致性。

TraceId 关联 HTTP、持久化文档任务与 MQ 消费。任务和消息携带 trace 信息，消费作用域结束恢复 MDC；缺少 originating trace 的旧任务不具备同等关联信息。统一异常与失败摘要避免回显敏感异常正文。

Actuator / Micrometer 提供已有健康与运行指标，Prometheus 从内部网络采集；公开 Nginx 不暴露 Actuator 路径。

## 部署与设计边界

[Docker Compose](../deploy/docker-compose.yml)组织 backend、frontend 与基础设施；本地启动及配置方式见 [README](../README.md#本地运行)。生产部署需独立准备凭证、TLS、网络隔离与迁移验证。

- 数据库事务的原子性不覆盖对象存储、模型调用与搜索索引。
- RabbitMQ 重试与幂等提交不等同于端到端 exactly-once。
- Cache Aside 为最终一致性；授权仍由数据库和应用权限逻辑判断。
- RAG 输出依赖语料、检索与模型能力，需分别验证相关性、引用和答案正确性。

更多实现细节与验证范围见 [后端工程说明](backend/BACKEND_ENGINEERING_REPORT.md)、[数据库分析](backend/DATABASE_ACCESS_REVIEW.md)和 [Benchmark 报告](benchmark/report.md)。完整设计历史与验证记录属于未随公开仓库提供的历史材料；公开文档提供当前设计、方法与代表性结果。
