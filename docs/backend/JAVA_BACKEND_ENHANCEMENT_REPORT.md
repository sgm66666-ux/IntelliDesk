# Java 后端增强实施报告

## 范围与结论

本报告记录 Phase 0–10 的实施与阶段验证，并追加 2026-10-05 的收尾验证。原实施阶段没有修改 README 或执行 Git 提交；收尾阶段获得了更新公开文档和 commit/push 的授权。两阶段均只修改 Public 工作副本，没有重新执行模型实验、企业评测或正式 Benchmark，没有改正式模型配置、企业语料、Ground Truth、历史证据或评分规则。原实施阶段受保护文件 30,863 项哈希一致；本次保全检查允许明确授权的文档与前端测试变化。原 IntelliDesk 未执行写操作。

**不能宣称 full regression PASS。** 2026-10-05 广范围业务回归仍有历史诊断 fixture 缺失。前端 router timeout 已经逐阶段计时定位并通过最小测试修复；5 次独立复验和完整前端测试均通过。下文保留原阶段结果，最新结论见末尾“发布收尾验证”。

## 1–3. 原有能力、新增能力与业务对应

审计见 [JAVA_BACKEND_BASELINE_AUDIT.md](JAVA_BACKEND_BASELINE_AUDIT.md)。实际栈为 Java 21、Spring Boot 3.5.8、Spring Security、JJWT 0.12.6、MyBatis-Plus 3.5.9、PostgreSQL/Flyway、Redis、RabbitMQ、MinIO、Elasticsearch、Spring AI 1.1.2，不是 JPA/MySQL。

| 能力 | 原有实现 | 本轮实际增强 | 业务场景 |
| --- | --- | --- | --- |
| Security/JWT/RBAC | stateless FilterChain、BCrypt、JWT、数据库角色权限、工作空间授权 | 单次解析、必要 claims 校验；补全失效/无效/撤权测试 | 登录后访问知识库、文档、API Key |
| 文档任务 | 持久化状态、attempt/lease/version、CAS、唯一约束、恢复调度 | 集中合法状态定义、补齐事务回滚、stale-attempt ABA 防护 | 上传后异步解析与分块 |
| RabbitMQ | durable 拓扑、手动 ACK、延迟队列、DLQ、publisher confirm/returns | 修正 retryExchange 解析；校验消息身份；真实 broker 测试 | 临时失败延迟处理、重复投递、永久失败诊断 |
| 异常/trace | Result、Advice、HTTP TraceId/MDC | 安全错误摘要、受限 TraceId、持久化并传播到 MQ、作用域清理 | 用户报错与后台任务关联 |
| Redis | refresh token、限流、既有 recovery lock | 新增知识库小型元数据 Cache Aside | 授权后的重复元数据读取 |
| 数据库 | 业务唯一约束、分页/租户索引、检索索引 | 一个租约扫描联合索引，真实迁移/EXPLAIN | 定时恢复超时任务 |
| 性能准备 | Actuator/Micrometer HTTP histogram/Prometheus | 三个现有只读 API 的离线验证压测脚本 | 后续隔离 Java 后端测试；本轮零压测请求 |

已有 RAG、Agent、Embedding、Vector、BM25、RRF、Reranker、Citation 的实现未修改。业务回归不等于对真实模型效果作出了新的验证结论。

## 4–6. Security、JWT 与 RBAC

HTTP → RateLimitFilter → ApiKeyAuthenticationFilter → JwtAuthenticationFilter → stateless SecurityContext → Controller @PreAuthorize → WorkspaceAuthorizationService。

登录用 BCrypt 验证数据库用户，生成含 userId/subject/type/jti/issuedAt/expiration 的 HMAC JWT；请求中只解析校验一次签名、到期时间、access 类型与必要身份字段。密码/凭证不进入 claims。已停用用户不认证。当前角色/权限每次从数据库读取，不把旧 JWT 角色当作最终授权事实。

保留真实 ADMIN/MEMBER 全局角色和 workspace owner/member，不人为添加 EDITOR/VIEWER。全局 authority 再叠加 workspace membership/owner 检查：成员读、owner 管理；API Key 还受已有 scope 限制。LLM 不参与权限决策。测试覆盖 401、403、owner 正常操作、跨工作空间拒绝、角色撤销后旧 JWT 被拒绝。

## 7–10. RabbitMQ、retry、DLQ、幂等

默认真实拓扑：

| 用途 | Exchange | Routing key | Queue |
| --- | --- | --- | --- |
| main | intellidesk.document.x | document.process | intellidesk.document.process.q |
| retry | intellidesk.document.dlx | document.process.retry | intellidesk.document.process.retry.q |
| dead | intellidesk.document.dlx | document.process.dead | intellidesk.document.process.dlq |

均为 durable direct exchange/queue。默认 retry/dead 共用 exchange 名称，允许配置为不同 exchange；本轮修复此前 retry 属性未真正用于绑定/发布的问题。retry queue TTL 保持原来的 30,000ms，过期回 main；数据库 next_retry_at 与调度恢复负责补偿丢失/未确认发布。maxAttempts 默认 3 是**总尝试数**，即最多两次重试，不是额外重试三次。

Consumer 手动 ACK，不无限 requeue。临时失败记录 RETRY_WAIT，超过次数为 DEAD，document 为 FAILED；持久化 DEAD 由恢复调度确认投递 DLQ。结构错误/消息身份不符则 nack(requeue=false) 进入 DLQ，不能伪造合法任务状态。publisher 使用 mandatory、confirm 和 returned-message 检查；DLQ 是诊断保留，不是自动无界重放。

幂等依据 durable taskId + messageId + documentId 身份、UUID 唯一约束、每文档活跃任务唯一约束、状态/attempt CAS、document version CAS、chunk(documentId,chunkIndex) 唯一约束。terminal/有效 PROCESSING lease 的重复消息 ACK 跳过；claim 竞争失败不执行解析。

成功提交但 ACK 前宕机：重投递看到 SUCCEEDED，跳过解析与 DB 写入。提交前宕机或租约失效：解析计算可能重新执行；attempt fence 防止旧 worker 提交，唯一约束和事务防重复持久化。**不承诺 exactly-once，也不承诺所有宕机场景都只解析一次。** 后续 retrieval task 独立持久化，已有 generation/fence/外部版本幂等逻辑未改。

## 11–12. 状态机与事务

保留任务值 PENDING / QUEUED / PROCESSING / RETRY_WAIT / SUCCEEDED / DEAD / CANCELLED。

- PENDING → QUEUED / PROCESSING / CANCELLED
- QUEUED → PROCESSING / CANCELLED
- RETRY_WAIT → QUEUED / PROCESSING / CANCELLED
- PROCESSING → SUCCEEDED / RETRY_WAIT / DEAD / CANCELLED
- SUCCEEDED / DEAD / CANCELLED 为终态，不能倒退；手动 retry 创建新的任务流程。

DocumentTaskStatus 集中定义 claimable 状态；数据库更新仍采用带前置状态和 fence 的条件 UPDATE，不引入另一套工作流引擎。任务记录已有 documentId、attemptCount/maxAttempts、短失败摘要、时刻与 version，本轮新增 traceId。

事务边界是短 DB 操作：task+document claim、chunk 持久化+SUCCEEDED/COMPLETED+retrieval task、失败状态的 task/document 同步更新、KB 写操作。竞争失败必须回滚任务更新。MinIO 读写、解析、分块计算、Embedding/ES 外部 I/O 不放入长 DB 事务；外部资源不受数据库事务原子性保证，继续依赖既有补偿任务。

Document 的 COMPLETED / ingestion task 的 SUCCEEDED 表示解析分块阶段完成，不等于检索索引一定 READY。Parse/Chunk 与后续 Embedding/Index 任务分离，未添加不存在的细分阶段。

## 13–14. Redis Cache Aside

仅知识库小型元数据；key 为 `intellidesk:kb:metadata:{workspaceId}:{kbId}`，序列化内容上限 8192 字符，TTL 300–360 秒。每次先查数据库 membership，再查缓存。MISS 查 DB 并填充；HIT 不免除权限检查。跨空间/错误 ID 缓存条目被拒绝。

DB 创建/更新/删除提交后失效匹配缓存；事务未提交不填充，回滚不执行 afterCommit。Redis 异常回退 DB，不让缓存故障破坏基本业务。失败失效与并发读填充仍可能带来短暂陈旧元数据，TTL 约束最终一致性；不声称强一致缓存。

不缓存权限最终判定、敏感一次性数据、正文或模型答案。不缓存不存在的 KB（缺失数据请求仍访问 DB），不添加 Bloom Filter/新的分布式锁。TTL jitter 缓解集中到期，但不等于彻底解决击穿/穿透/雪崩；需要根据真实授权流量决定下一步。

## 15–16. 索引与 EXPLAIN

唯一新增索引为 V7 `document_index_task(status,lease_until)`：status 等值在前，lease 范围/ORDER BY 在后。既有 dispatch 索引的第二列是 next_retry_at，不覆盖 lease 查询的范围与排序。

隔离 PostgreSQL 16 中使用 20,000 合成文档/任务，1,000 PROCESSING、50 过期租约；新索引之前扫描活跃任务、过滤并排序，之后使用 idx_task_status_lease 范围/有序 limit。SELECT * 仍需回表，新增 B-tree 增加存储及 task 状态/租约更新的写入成本。KB/document 列表使用已有索引，不盲加重复索引。

真实计划见 [sql-plans.json](sql-plans.json)，分析见 [DATABASE_ACCESS_REVIEW.md](DATABASE_ACCESS_REVIEW.md)。单次 EXPLAIN 时延不是正式性能指标。V1–V5 未修改，V6 增加 nullable traceId 兼容旧任务；尚未迁移现有业务数据库。leading-wildcard LIKE/deep OFFSET 的潜在成本没有通过擅自改变查询语义来掩盖。

## 17–18. 乐观并发与 TraceId

document 状态沿用 version CAS。任务 claim 使用 status + snapshot attempt_count CAS 并约束 attempt_count < max_attempts，解决跨 retry 周期的 stale snapshot/ABA；不采用全 task-version 比较，以免与 PENDING→QUEUED 的正常 dispatch 产生不必要竞争。真实两个并发事务验证恰好一个成功。

TraceIdFilter 限制外部 trace 字符/长度，保存在 request attribute、ThreadLocal/MDC 与响应头；新的文档任务持久化 originating traceId，publisher 将其放入消息，consumer 同时关联 taskId/documentId/messageId。AutoCloseable scope finally 恢复/清理线程上下文，包括错误和 nack 路径；不清空其他组件合法 MDC。旧任务缺失 originating trace 时不能假造历史关联。统一异常与任务失败摘要不返回原始外部异常正文/内部路径/凭证。

## 19. 测试结果（非重复累计）

新增 **53 个 Java 测试**：12 个新测试类及现有 Consumer/RBAC 类新增 8 个测试；全部已实际通过。另新增 3 个 Node 离线脚本测试，3/3 PASS，零 HTTP 请求。

| 验证 | Maven 权威汇总 | 结果 |
| --- | --- | --- |
| Phase 1 | 54 / 0 failure / 0 error / 0 skip | PASS |
| Phase 2 | 27 / 0 / 0 / 0 | PASS |
| Phase 3 修正后 | 28 / 0 / 0 / 0 | PASS，真实 30 秒 TTL/DLQ |
| Phase 4 | 20 / 0 / 0 / 0 | PASS |
| Phase 5 | 27 / 0 / 0 / 0 | PASS |
| Phase 6 | 18 / 0 / 0 / 0 | PASS |
| Phase 7 修正后 | 22 / 0 / 0 / 0 | PASS，真实 Redis |
| Phase 8 迁移 | 3 / 0 / 0 / 0 | PASS，真实 PostgreSQL |
| Phase 9 | 16 / 0 / 0 / 0 | PASS，真实 MyBatis 并发 |
| 安全复查 | 26 / 0 / 0 / 0 | PASS |
| JWT 测试输入扫描修正后复验 | 31 / 0 / 0 / 0 | PASS |
| 最终广范围业务回归（97 个类，含 nested tests） | **923 total / 901 passed / 0 failure / 2 error / 20 skipped** | **FAIL，不能称全绿** |

计数采用 Maven 最终汇总。该运行环境的部分 top-level XML 摘要不累计全部 nested tests，Phase 6 先前 XML-only 统计为 13，最终广范围 XML-only 为 386；原始记录保留，另存 Maven 权威合计，不把局部 XML 统计冒充全部结果。各阶段有重复测试，表中数字不能相加当成新增测试数。

Phase 3/7 首次运行因新增测试笔误编译失败，修复后重新执行，首次日志保留；没有删除断言或降低测试标准。Secret scan 的无效 JWT fixture 字面量误报，经核验后改为运行时拼接完全相同输入；扫描规则未修改。

用户要求停止的 benchmark/evaluation/evidence 实验类没有运行。因此本轮也不是默认所有后端测试的全量 PASS。

## 20. Phase 0–10 结束时的验证问题（历史记录）

1. `DiagnosticBeanRouteTest.realQueryRewriteAndFinalStreamingPassThroughSameGuardedClient`：访问既有 capture fixture 18303 /arm 时 ResourceAccess 错误。
2. `TransparentCaptureHttpTest.realSpringSyncChunkedAndStreamingAreEquivalent`：访问既有 fixture 18302 /records 时 ResourceAccess 错误。
3. 20 项既有 integration skip 依赖未运行的 HTTP provider fixture（日志含 18082）。未删除/skip 测试，未启动历史 capture 实验以获取全绿。
4. 未修改的前端完整回归 18/19 files、94/95 tests PASS；`allows authenticated user to access /workspaces` 超过 5000ms。单独三次 RUN1 FAIL 17.047s、RUN2 PASS 2.219s、RUN3 PASS 2.125s。因为出现重复失败，没有继续完整重跑或生产构建，没有改 timeout/断言/router。

前端只读定位：fixture 已设置 bootstrapReady=true，当前测试没有等待登录 HTTP、router.isReady 或 fake timers；等待点是 router.push，包括 WorkspaceListPage 懒加载及 Element Plus 等依赖解析。后两次页面解析约 0.8 秒，冷加载/环境开销是线索，但缺少逐阶段计时，**尚未证明最终根因**。不能把它报告成已修复的偶发问题。

缓存最终一致性、提交前重复解析、外部索引/存储补偿、未执行压力测试属于明确设计边界，不包装成强一致/exactly-once/高可用能力。生产运行前还需正常迁移与部署验证，本轮没有自动更换生产服务。

## 21–22. 可真实写进简历 / 禁止宣称

可写：维护并强化 Spring Security/JWT 与数据库 RBAC+workspace 授权；为文档异步链路完善 RabbitMQ 延迟重试、DLQ、数据库 CAS 幂等提交与事务补偿；KB metadata Redis Cache Aside、提交后失效；HTTP→MQ trace 关联；基于真实 PostgreSQL EXPLAIN 增加租约查询联合索引；用 Testcontainers 验证 RabbitMQ/Redis/PostgreSQL 与并发冲突。

不能写：从零新增了项目原本就有的完整认证/MQ系统；全量测试全绿；高并发/高可用/生产 SLA；没有测得的 QPS/P95/提升百分比；全链路 exactly-once、所有宕机绝不重复解析、强一致缓存；新增微服务/Kafka/Nacos/Seata/K8s；解决所有缓存经典问题；本轮提升模型质量或改变企业评测结果。没有自动编辑简历。

## 23. 十个真实面试追问

1. 为什么 JWT 不缓存最终角色权限？数据库角色撤销如何影响已有 Token？
2. 全局 authority、workspace member/owner 与 API Key scope 如何叠加防止 IDOR？
3. Rabbit publisher confirm 与 mandatory return 各解决什么问题？DB dispatch 状态如何补偿发布故障？
4. retry TTL 与 DB next_retry_at 双路径为什么可能重复投递，怎样确保只提交一次？
5. 成功提交后 ACK 前宕机，和解析完但提交前宕机，分别发生什么？
6. 为什么 claim 不比较全 task version，而比较 status/attempt？如何阻止 ABA/stale worker？
7. 数据库事务为什么不能包住 MinIO、解析和 Embedding/ES？外部故障如何补偿？
8. Cache Aside 为什么提交后失效？失效与并发读填充竞争、Redis 故障时如何限制陈旧窗口？
9. 为什么新索引是 (status,lease_until)，而 dispatch 索引不够？何时仍需回表、怎样衡量写成本？
10. HTTP TraceId 如何穿过 RabbitMQ，线程池 MDC 怎样清理？非 LLM QPS 与任务完成时延为何不能混为一谈？

## Phase 0–10 保全与 Git（历史记录）

已有 public secret scan PASS；真实 .env 未被追踪，无 staged 文件。新增文件与现有 dirty/untracked 文件分开核验，本轮 backend 为 18 个修改文件 + 16 个新增文件；还有本报告/审计/SQL/性能说明与离线脚本。README/.gitignore 等既有 dirty 状态没有被回滚或提交。

没有改原 IntelliDesk，没有 commit/push，没有新增框架、角色体系或分布式组件。到此停止，不自动恢复历史实验或创建正式压测。

## 发布收尾验证（2026-10-05）

本次只修复前端 guard 测试的依赖加载边界，并更新公开文档；没有再次改变 Java 生产实现。测试/构建前后检查 backend/src/main 与 frontend/src 非测试文件，均没有被执行过程改写。Docker Server 29.8.0 可用；真实 RabbitMQ、Redis、PostgreSQL、Elasticsearch 集成测试按原配置执行。

| 验证 | 最终真实结果 | 进程耗时 / exit |
| --- | --- | --- |
| 新增 Java 定向回归 | 53 total、53 PASS、0 failure、0 error、0 skip | 67.172s / 0 |
| 当前工作树 broad backend（97 类，含 nested） | 923 total、901 PASS、0 failure、2 ERROR、20 SKIP | 369.985s / 1 |
| router RUN1 | 4/4 PASS | 2.234s / 0 |
| router RUN2 | 4/4 PASS | 2.187s / 0 |
| router RUN3 | 4/4 PASS | 2.156s / 0 |
| router RUN4 | 4/4 PASS | 2.297s / 0 |
| router RUN5 | 4/4 PASS | 2.656s / 0 |
| frontend 全部单元测试 | 19/19 files、95/95 tests PASS | 3.781s / 0（Vitest 2.97s） |
| frontend production build | vue-tsc -b && vite build PASS | 6.109s / 0 |
| backend package | compile/testCompile/package 成功，测试执行已明确跳过 | 3.828s / 0 |
| non-LLM 脚本离线检查 | 3/3 PASS，零 HTTP 请求 | Node 汇总 63.473ms / 0 |

后端 package 命令是 `mvn -B -ntp -f backend/pom.xml -DskipTests package`，不是“测试 PASS”。前端实际使用 `npm test -- --run`、`npm run build`；router 每次执行 `npm test -- --run src/__tests__/router.test.ts --reporter=verbose`，不增加 timeout。

定向 Java 选择 12 个新增类，以及 DocumentTaskConsumerTest 新增的 4 个 identity/commit-redelivery 方法、ApiKeyRbacTest 新增的 4 个授权方法，计 53。广范围选择 backend/src/test/java 下以 Test.java 结尾且不在 benchmark/evaluation/evidence 包中的 97 个类，使用 Maven `-Dtest=<明确类名列表>`；未执行模型实验与正式 Benchmark。不能称为默认 Maven 全部测试通过。

### KNOWN HISTORICAL FIXTURE DEPENDENCY

- `TransparentCaptureHttpTest.realSpringSyncChunkedAndStreamingAreEquivalent`：GET `http://127.0.0.1:18302/records`，ResourceAccess I/O error。
- `DiagnosticBeanRouteTest.realQueryRewriteAndFinalStreamingPassThroughSameGuardedClient`：POST `http://127.0.0.1:18303/arm`，ResourceAccess I/O error。
- ChatLlmHttpIntegrationTest 8 SKIP、Phase4Wave3IntegrationTest 8 SKIP、QueryRewriteHttpIntegrationTest 4 SKIP：既有 18082 HTTP fixture 不可用。

以上测试与判断条件均未删除、修改、mock 或 ignore。没有启动历史 capture/provider 服务来补全测试。两个 ERROR 对应的历史诊断源码是收尾前就存在的未跟踪实验文件，此次仍原样保存在本地，不混入 Java 增强提交。因此上述 923 是明确的**当前本地工作树广范围验证**，不是声称一个干净 clone 一定枚举同样的 923 个测试。依赖未公开历史归档的 benchmark/tooling 检查同样不冒充默认可复现全绿。

### Router 根因与最小修复

失败为 `router.test.ts` 中 `allows authenticated user to access /workspaces`。诊断仅为现有 guard、lazy loader、beforeResolve 增加逐阶段计时，保留原始失败日志后移除探针：guard 完成 0ms，WorkspaceListPage 第一次真实导入 8455ms，后续 7ms / 0ms。前一导航在超时后才完成，日志跨到了下一测试；pending Promise 是页面模块导入，不是未结束的认证请求。

router.push 已 await；bootstrapReady=true 路径不执行 refresh；未使用 fake timers；没有 mount 页面，所以不会运行页面 onMounted 请求，也没有组件卸载遗漏。router.isReady 不是这次额外等待点。静态源码与计时证据共同指向：guard 单元测试把 Element Plus/Vue 页面依赖的冷解析算入了 5000ms 断言窗口。

最小修改仅在测试模块静态导入真实 LoginPage 与 WorkspaceListPage，模块加载发生在 test case 计时前。仍走生产 createAppRouter、真实导航、auth store 与原始四项断言；没有 mock 页面、删测试、改 fixture 语义或提高 timeout。5 次独立进程复验均通过，完整 95 测试通过；测试中的断言执行约 6–8ms，模块导入成本仍被 Vitest 计入 import 时间，没有隐藏它。

### 文档与发布边界

README 调整为 Java / Spring Boot 企业知识库与智能问答平台，准确说明 MQ 幂等、事务和缓存边界；只链接本次发布实际包含的报告与已有截图。此前未提交的企业 Showcase/模型实验资料留在本地，历史文件不删除、结果语义不改。架构文档标明早期草案与当前实际实现的区别。所有公开路径使用仓库相对路径。

现有 public candidate secret scanner 覆盖 Git-visible 源码、配置、测试和文档；真实 .env、raw provider/runtime 输出、缓存、模型和构建产物不进入提交。已有扫描规则和按文件哈希绑定的 synthetic fixture 例外保持不变。最终 staged review 使用逐项清单，不执行 git add .。

最终扫描 PASS（863 个文本文件、888 个 Git-visible candidates、10 个既有 hash-bound fixture 例外）。本轮基线 889 个文件仅 README、架构说明、当前报告、router 测试发生授权变化；此前 30,863 个保护项仅 README 因本次明确授权改变，其他保护项及真实 .env 均保持哈希一致。README/报告 17 个相对链接均指向拟发布文件。

额外从实际暂存区导出隔离后端源码，未复制任何未跟踪 Java 实验文件或原 target，再执行同一 `-DskipTests package`：全新 compile/testCompile/package PASS，exit 0、12.078s。这证明公开后端构建不依赖本地历史实验源码；仍不是新的测试 PASS。提交清单为 46 个文件（22 modified、24 added、0 deleted）：后端实现/SQL 20、后端与前端测试 15、脚本 3、Git ignore 1、文档 7；没有新增 binary、模型、raw evidence 或大文件。

仍保留的限制：历史 fixture ERROR/SKIP；Vite 提示部分 bundle 超过 500kB；测试配置提示未来 native configLoader 对 __dirname 的兼容性。这些不是本次生产构建失败。Spring 测试上下文缓存的 scheduler 在 Testcontainer 结束后还可能输出连接拒绝日志，未将日志噪声算成额外 assertion failure，也未据此修改生产逻辑。缓存最终一致性、提交前重复解析及无新负载测试仍是设计/验证边界，不影响如实展示本轮已验证的 Java 工程能力。
