# IntelliDesk

**Java / Spring Boot 企业知识库与智能问答平台**

IntelliDesk 是一个模块化单体项目，围绕工作空间授权、异步文档处理和可追溯知识问答，实践 Java 后端的安全、事务、消息重试、缓存与并发状态保护。Vue 前端提供知识库管理、文档状态、流式对话、引用来源和 API Key 管理。

## 系统架构

~~~text
Vue 3 → Nginx → Spring Boot
                  ├─ Spring Security / JWT / RBAC / workspace authorization
                  ├─ PostgreSQL + MyBatis-Plus：业务数据、持久化任务、pgvector
                  ├─ RabbitMQ：文档任务、延迟重试、DLQ
                  ├─ Redis：refresh token、限流、知识库元数据 Cache Aside
                  ├─ MinIO：文档对象
                  ├─ Elasticsearch：BM25 检索索引
                  └─ Spring AI：聊天模型、Embedding 与现有 RAG / Agent
~~~

详见[架构说明](docs/ARCHITECTURE.md)与[Java 后端增强报告](docs/backend/JAVA_BACKEND_ENHANCEMENT_REPORT.md)。数据库、消息队列、对象存储是独立基础设施，不把该项目描述为微服务系统。

## 核心 Java 后端能力

| 能力 | 实际实现 |
| --- | --- |
| 认证与授权 | Spring Security、JWT、BCrypt、数据库 RBAC、工作空间成员/owner 校验、API Key scope |
| 异步任务 | 持久化 DocumentTask、手动 ACK、publisher confirm/return、延迟重试与 DLQ |
| 状态与事务 | 状态机、task status/attempt CAS、document version CAS、失败竞争回滚 |
| 缓存 | 小型 KB metadata Cache Aside、授权先于缓存读取、提交后失效、Redis 故障回退 |
| 数据访问 | PostgreSQL、MyBatis-Plus、唯一约束、租约扫描联合索引与真实 EXPLAIN |
| 排错 | HTTP → 持久化 task → MQ TraceId/MDC、安全的统一响应与失败摘要 |

## AI / RAG 能力

- Markdown、TXT、PDF 解析分块；Embedding 写入 pgvector，关键词索引写入 Elasticsearch。
- Vector、BM25、Hybrid + RRF；可选 Reranker，不保证排序一定改善。
- Query Rewrite、上下文构建、引用校验、SSE 增量回答。
- 有界 Agent 工具调用、参数校验、结果脱敏和可查看的工具轨迹。

现有[受控 RAG Evaluation](docs/evaluation/report.md)使用 **SYNTHETIC / FICTIONAL** 语料：14 份文档、42 个实际 Chunk、69 个问题，其中 60 个合格问题参与质量指标，9 个无相关证据问题单列。在这份受控语料上，**HYBRID_RERANK 低于 HYBRID**；BM25 的 chunk MRR 也高于 Hybrid，因此不能宣称 Hybrid + RRF 普遍更优。检索相关性指标不等于答案正确率。

项目完成过受控 RAG Evaluation、Benchmark 和 Failure Testing；[代表性 Benchmark 报告](docs/benchmark/report.md)保留测量范围和限制。本次 Java 增强没有重新运行模型评测，也没有产生新的性能或答案质量结论。不同历史语料与运行口径不得混合比较。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.5.8、Spring Security、MyBatis-Plus 3.5.9、JJWT 0.12.6 |
| AI 接入 | Spring AI 1.1.2、可配置的聊天与 Embedding Provider |
| 基础设施 | PostgreSQL 16 + pgvector、Redis、RabbitMQ、MinIO、Elasticsearch；既有 Flyway 迁移 |
| 前端 | Vue 3、TypeScript、Vite、Pinia、Element Plus |
| 验证与部署 | JUnit 5、Mockito、MockMvc、Testcontainers、Vitest、k6、Docker Compose、Nginx |

## 文档处理流程

~~~text
上传并授权 → MinIO → 持久化 PENDING 任务 → MQ / dispatcher
    → 原子 claim PROCESSING → 事务外解析与分块
    → 同一 DB 事务保存 Chunk、SUCCEEDED / COMPLETED 和 retrieval task
    → 独立检索任务完成 Embedding / Elasticsearch 索引
~~~

解析分块完成不等于检索索引已 READY。外部存储、模型与索引调用不放入长数据库事务；失败通过已有持久化任务和补偿流程恢复。

## 安全模型

JWT 校验签名、到期时间、access 类型和必要身份字段；请求的角色权限从数据库读取，撤权不依赖等待旧 JWT 到期。全局 ADMIN/MEMBER 权限再叠加 workspace member/owner 检查；API Key 同时受 scope 约束。

密码使用 BCrypt；完整 API Key 仅创建时返回一次，后续展示 metadata。LLM 不参与授权。真实凭证只放本地环境配置，不进入 Git、日志示例或截图。

## RabbitMQ：重试、DLQ 与幂等

- 持久化 exchange/queue，mandatory 发布、confirm/return 检查，consumer 手动 ACK。
- 默认重试队列 TTL 为 30 秒；默认 3 次是总尝试数，不是额外重试 3 次。
- 临时失败进入 RETRY_WAIT；耗尽后 DEAD，保留 DLQ 诊断，不自动无界重放。
- 消息身份与数据库 taskId、documentId、messageId 一致后才处理；状态/attempt 条件更新和 document version CAS 防止竞争提交。

**成功提交后的重复投递可避免再次处理；提交前故障仍可能重新执行。** 数据库唯一约束、事务和 attempt fence 保护持久化结果，不承诺端到端 exactly-once 或零消息丢失。

## Redis Cache Aside

仅缓存小型知识库元数据，key 包含 workspaceId 与 kbId，内容上限 8192 字符，TTL 为 300–360 秒。每次先查数据库授权，再读取缓存。MISS 回源；DB 写入提交后失效；未提交事务不填充缓存；Redis 异常回退数据库。

缓存是有界最终一致性，不缓存最终权限判断、正文、模型答案或一次性凭证。并发填充、失效失败仍可能造成短暂陈旧数据，不承诺强一致。

## 数据库、事务与索引

短事务协调 task/document claim、Chunk 保存与后续任务创建；状态竞争失败会回滚关联更新。任务 claim 校验 status + attempt_count，文档保留 version CAS，防止旧执行者跨重试周期提交。

新增租约索引为 document_index_task(status, lease_until)。[数据库分析](docs/backend/DATABASE_ACCESS_REVIEW.md)包含隔离 PostgreSQL 上 20,000 条合成任务的真实 EXPLAIN：该索引支持状态等值、租约范围与排序，但增加写入维护成本；单次计划时延不是吞吐提升承诺。既有唯一约束和适用的分页索引继续保留。

## TraceId 与错误处理

入口限制 TraceId 字符和长度，将其关联到响应、持久化文档任务及 MQ 消费；MDC 同时带 task/document/message 标识，作用域结束恢复线程上下文。GlobalExceptionHandler 与文档失败摘要不直接回显外部异常正文或敏感机器路径。

这不是分布式追踪平台；旧任务没有保存的 originating trace 无法追溯补造。

## 测试与验证

覆盖 JWT/RBAC、工作空间隔离、事务回滚、任务状态保护、MQ 重试/DLQ、缓存授权和真实 Redis/PostgreSQL/RabbitMQ 集成行为。本轮新增 53 项 Java 测试通过；前端 19 个测试文件、95 项测试通过；3 项新增脚本离线检查通过。

**广范围后端回归并非全绿**：既有历史 HTTP capture fixture 缺失产生 ERROR，部分 HTTP 集成测试因 fixture 缺失 SKIP。范围、命令、准确计数、router 稳定性复验与构建结果见[完整验证说明](docs/backend/JAVA_BACKEND_ENHANCEMENT_REPORT.md)。不将跳过测试的 package 称为测试通过。

~~~powershell
# 后端：部分集成测试要求 Docker；默认测试还可能依赖本地历史归档/fixture
mvn -f backend/pom.xml test

# 前端
cd frontend
npm ci
npm test -- --run
npm run build
~~~

[非 LLM 性能准备](docs/backend/NON_LLM_PERFORMANCE_PREPARATION.md)仅包含受限只读脚本与离线测试，本轮没有执行新的压测或正式 Benchmark。

## 本地开发

准备 Docker Compose 和实际可用的聊天、Embedding Provider。只在尚无 .env 时复制模板，随后按本机服务填写；不要用模板覆盖现有真实配置。

~~~powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
~~~

Web 默认位于 http://localhost/，backend 在 Compose 内通过 Nginx 访问。环境项见[配置模板](.env.example)；模型必须由使用者根据实际 Provider 配置，不以“本机装过某模型”替代项目配置。

前后端独立开发需要 Java 21、Maven、Node.js 与对应依赖服务。先将自己的环境配置安全注入后端进程，再运行：

~~~powershell
mvn -f backend/pom.xml spring-boot:run
# 另一个终端，在 frontend 目录
npm ci
npm run dev
~~~

Vite 的 /api 代理默认指向 localhost:8080。生产部署仍需独立完成凭证、TLS、数据库迁移、网络隔离与实际工作负载验证。

## 真实界面

![知识库与文档管理](docs/demo/screenshots/knowledge-base-documents.png)

![流式问答与引用](docs/demo/screenshots/rag-conversation-citations.png)

更多既有截图：[文档 Chunk](docs/demo/screenshots/document-detail-chunks.png)、[API Key metadata](docs/demo/screenshots/api-key-metadata.png)、[Agent 工具轨迹](docs/demo/screenshots/agent-tool-trace.png)。

## 已知边界

- 当前证据不支持生产 SLA、高可用或未经测量的 QPS/P95 指标；本项目不承诺这些能力。
- 重试不等于只执行一次，数据库事务也不覆盖外部存储和索引。
- 元数据缓存可能短暂陈旧；深分页和前置通配符查询仍有成本。
- RAG 可能检索错源或生成失败；引用存在不代表答案正确，Reranker 不能保证提升效果。
- 受控合成语料不是实际企业数据或真实业务效果证明。
- 缺失历史 fixture 的测试限制仍保留；前端构建还有 bundle 大小提示。
- 此次提交仅包含 Java 增强、测试修复及公开说明，不发布本地历史模型实验、raw provider 输出或内部归档。

完整验证与历史证据保留于本地归档，公开仓库仅保留源码、方法说明与代表性结果。
