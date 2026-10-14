# Backend Engineering Report

本文说明 IntelliDesk 当前后端的实现机制、设计边界和公开测试入口。应用采用 Java / Spring Boot 模块化单体，使用 Spring Security、MyBatis-Plus、PostgreSQL、Redis、RabbitMQ、MinIO、Elasticsearch 与 Spring AI。整体结构见 [架构说明](../ARCHITECTURE.md)。

## 认证与资源授权

请求经过限流和认证 Filter，再由 Controller 与 workspace 授权服务检查访问范围。JWT 校验签名、到期时间、access 类型与必要身份字段；角色权限从数据库读取，撤权不依赖旧 JWT 到期。登录密码使用 BCrypt，已停用用户不认证。

全局 ADMIN/MEMBER authority 与 workspace member/owner 校验叠加，API Key 还受 scope 约束。LLM 不参与授权。完整 API Key 仅创建时返回一次，后续接口仅提供 metadata。

## RabbitMQ、重试与幂等提交

默认拓扑如下：

| 用途 | Exchange | Routing key | Queue |
| --- | --- | --- | --- |
| main | intellidesk.document.x | document.process | intellidesk.document.process.q |
| retry | intellidesk.document.dlx | document.process.retry | intellidesk.document.process.retry.q |
| dead | intellidesk.document.dlx | document.process.dead | intellidesk.document.process.dlq |

Exchange/queue 为 durable，retry/dead exchange 可分别配置。默认 retry queue TTL 为 30,000ms，过期回 main；数据库 `next_retry_at` 与调度恢复协调补偿发布。默认 `maxAttempts=3` 是总尝试数，即最多两次重试。

Consumer 使用手动 ACK。临时失败进入 RETRY_WAIT，尝试耗尽后任务为 DEAD、文档为 FAILED；结构或消息身份不符时拒绝无界 requeue。发布端检查 mandatory、confirm 和 returned message，DLQ 保留诊断消息。

幂等提交依赖 taskId/messageId/documentId 身份、唯一约束、status/attempt CAS 和 document version CAS。成功提交后、ACK 前宕机，重投递可跳过解析与数据库写入；提交前故障或租约失效仍可能再次计算。该机制保护持久化结果，不承诺所有故障下只执行一次解析或端到端 exactly-once。

## 状态与事务边界

Ingestion task 使用 PENDING、QUEUED、PROCESSING、RETRY_WAIT、SUCCEEDED、DEAD、CANCELLED。数据库条件更新校验前置状态、尝试次数与执行 fence，限制旧执行者跨重试周期提交；document 状态更新保留 version CAS。

短事务协调 task/document claim、Chunk 保存、SUCCEEDED/COMPLETED 与后续 retrieval task 创建；竞争失败时回滚关联更新。MinIO、解析、Embedding 与 Elasticsearch 调用位于事务外，外部失败通过持久化任务与补偿流程处理。

Document COMPLETED / ingestion task SUCCEEDED 表示解析分块完成；独立检索任务的 READY 才表示该任务完成检索索引阶段。

## Redis Cache Aside

仅缓存小型知识库元数据，key 为 `intellidesk:kb:metadata:{workspaceId}:{kbId}`，序列化内容上限 8192 字符。每次先查数据库授权，再读取缓存；MISS 回源填充，跨空间或错误 ID 的条目被拒绝。

TTL 从单个条目最后一次写入 Redis 起计算，为 300–360 秒。数据库写操作提交后失效；未提交事务不填充，回滚不执行 afterCommit。Redis 异常回退数据库。

并发旧值回填或失效延迟可能产生短暂陈旧数据，回填会重新开始该条目的 TTL。缓存采用最终一致性，不保存最终授权判断、正文、模型答案或一次性凭证。

## 数据库访问与索引

租约扫描索引为 `document_index_task(status, lease_until)`：status 等值在前，lease 范围和排序在后。既有 dispatch 索引的 `next_retry_at` 服务另一访问路径。

隔离 PostgreSQL 中的 20,000 条合成文档/任务（1,000 PROCESSING、50 个过期租约）用于真实 EXPLAIN 分析。新索引支持范围扫描和有序 limit；SELECT * 仍需回表，B-tree 也增加存储和状态/租约更新成本。详见 [数据库分析](DATABASE_ACCESS_REVIEW.md)与 [SQL 计划](sql-plans.json)。单次查询计划不是吞吐指标或生产性能保证。

## TraceId 与异常处理

TraceIdFilter 限制输入字符和长度，关联 request、ThreadLocal/MDC 与响应头。文档任务持久化 originating traceId，publisher 将其传入消息，consumer 同时关联 taskId/documentId/messageId，作用域结束恢复线程上下文。旧任务缺少 originating trace 时不能补出历史关联。

统一异常与任务失败摘要避免返回原始外部异常正文、机器路径或凭证。TraceId 用于请求和异步任务关联，覆盖范围以实际保存的 trace 信息为准。

## 公开测试入口与前提

在仓库根目录使用 Java 21 与 Maven。README 的定向入口选择以下测试类：

| 入口 | 测试类 | 环境与范围 |
| --- | --- | --- |
| 单元测试 | `JwtTokenProviderTest`、`DocumentTaskConsumerTest`、`KnowledgeBaseMetadataCacheTest` | JWT、消息处理与缓存行为；使用自身 fixture / Mockito，不需要真实聊天模型 |
| Testcontainers 集成测试 | `DocumentTaskConcurrencyIntegrationTest`、`KnowledgeBaseRedisIntegrationTest`、`DocumentRabbitTopologyIntegrationTest` | 可用 Docker daemon 与镜像拉取权限；pgvector/PostgreSQL、Redis、RabbitMQ；真实 retry TTL 用例约需等待 30 秒 |

默认 `mvn -f backend/pom.xml test` 会发现更多测试。HTTP Provider 测试需准备其指定 fixture；正式 Evaluation / Benchmark 还需匹配语料、冻结配置和历史归档输入。公开定向入口不等于默认全量回归，依赖私人输入的历史检查不能在缺少输入时解释为通过。

前端入口为 `npm test -- --run` 和 `npm run build`。现有非 LLM 只读脚本的离线检查为 `node --test scripts/backend/non_llm_http.test.mjs`，发送零 HTTP 请求；脚本本身不构成新的性能测量结果。

## 历史验证范围与结果

以下为原报告保存的 **2026-10-05** 验证摘要，未在本文整理过程中重新运行：

| 检查范围 | 原记录结果 | 解读 |
| --- | --- | --- |
| Java 定向回归 | 53 total、53 PASS、0 failure、0 error、0 skip | 指明选择范围的测试通过 |
| 当时本地工作树广范围后端回归（97 类，含 nested tests） | 923 total、901 PASS、0 failure、2 ERROR、20 SKIP | 未通过；包含未发布诊断文件和依赖 HTTP fixture 的检查，不代表干净 clone 会枚举相同数量 |
| 前端完整单元测试 | 19/19 files、95/95 tests PASS | 当时记录的前端测试结果 |
| 前端生产构建 | vue-tsc -b && vite build PASS | 构建成功，不替代其他验证 |
| 后端 package | compile/testCompile/package 成功，测试执行已明确跳过 | 使用 `-DskipTests package`，不是测试通过 |
| 非 LLM 脚本离线检查 | 3/3 PASS，零 HTTP 请求 | 仅验证脚本配置与行为 |

上述后端 ERROR/SKIP 与历史 HTTP fixture 不可用有关；完整日志、阶段统计和排障过程属于未随公开仓库提供的历史验证材料。依赖历史 raw/manifest 的工具检查也需匹配这些材料。不能将这些定向结果合并成“全量测试全部通过”。

RAG 相关性与性能结果分别见 [Evaluation](../evaluation/report.md)和 [Benchmark](../benchmark/report.md)。业务测试、构建、检索指标与模型答案正确性是不同验证目标，结论限定于各自运行范围。
