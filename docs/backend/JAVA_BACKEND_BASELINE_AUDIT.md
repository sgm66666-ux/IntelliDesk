# JAVA_BACKEND_BASELINE_AUDIT

Audit date: 2026-10-01. This is a source/configuration audit, not a claim that regression has passed. README, model configuration, corpus, Ground Truth and historical evaluation are outside this change.

| Capability | Baseline | Verified implementation / gap |
| --- | --- | --- |
| Java / Spring Boot | EXISTING | Java 21, Spring Boot 3.5.8; Maven `backend/pom.xml`. |
| Spring Security | EXISTING | Stateless SecurityFilterChain, method security; rate limit → API Key → JWT → controller authorization. Custom JSON 401/403 handlers. |
| JWT / passwords | EXISTING | JJWT 0.12.6 verifies HMAC signature and expiry; access type/userId/subject/jti claims. BCrypt; Redis refresh token rotation via atomic Lua GET+DEL. Missing dedicated JWT edge-case tests. |
| RBAC / tenancy | EXISTING | sys_user, sys_role, sys_permission and join tables. ADMIN/MEMBER roles, permission authorities loaded from DB, workspace owner/member checks. No need for a parallel ADMIN/EDITOR/VIEWER system or LLM authorization. |
| Persistence | EXISTING | PostgreSQL + MyBatis-Plus/JDBC + Flyway; not JPA, not MySQL. UUID/JSONB/pgvector handlers. |
| Redis | PARTIAL | Dependency/template, refresh tokens, rate limiting and existing recovery lock. KB metadata Cache Aside is MISSING; final permission decisions and LLM answers are not cached. |
| Document tasks | PARTIAL | Durable document_index_task; enum PENDING/QUEUED/PROCESSING/RETRY_WAIT/SUCCEEDED/DEAD/CANCELLED, attempt limits, lease, bounded errors, timestamps/version/message UUID. Legal transitions are scattered across CAS updates rather than a shared definition. |
| Transactions | EXISTING | TransactionTemplate for atomic claim, chunk persistence/task completion/retrieval handoff, upload compensation; @Transactional for auth/workspace/KB/task services. Remote parsing/storage/model calls outside short DB transactions. Final document-update count needs defensive rollback verification. |
| RabbitMQ | EXISTING | Durable direct exchanges/queues, mandatory publish + correlated confirms + returned-message check; manual ACK, rejected requeue=false, default prefetch/concurrency=1. |
| Retry / DLQ | PARTIAL | Retry TTL + DB next_retry_at, max_attempts default 3; recovery scans expired leases and DEAD records. Retry exchange property exists but publisher/topology use dead-letter exchange directly; tests need distinct-exchange coverage. |
| Idempotency | EXISTING | Unique message UUID/active task per document/chunk position; atomic task status claim + document version CAS; attempt fencing on completion; terminal/active-lease duplicate skip; ES external version in retrieval indexing. Not exactly-once: crashed pre-commit parsing may repeat, but stale results must not persist. |
| Unified errors | PARTIAL | RestControllerAdvice and Result(code,message,data,traceId), validation/business/security/integrity/unknown errors. Explicit safe storage/parser/provider mapping and leakage tests need completion. |
| TraceId / MDC | PARTIAL | HTTP filter/ThreadLocal/response header; accepts unrestricted inbound trace and clears all MDC. MQ payload creates a new random trace instead of keeping originating request; consumer logs it but does not scope MDC. |
| MinIO | EXISTING | Upload/download/delete document objects; compensation on failed upload and deferred deleting cleanup. |
| Retrieval / AI | EXISTING | Parse→Chunk stores DB chunks and durable retrieval task in one transaction; retrieval worker performs Embedding→pgvector/Elasticsearch indexing with generation/fence; Vector/BM25/RRF/Reranker/Citation/Agent retained. No model quality changes authorized. |
| Database indexes | EXISTING | Tenant/time ordered KB/document indexes; unique checksum/task UUID/active task/chunk-position; dispatch and retrieval lease indexes; HNSW; chat/API-Key indexes. Analyze actual dispatcher predicates before adding nonredundant indexes. |
| Optimistic concurrency | EXISTING | Explicit status/attempt/version CAS; no @Version plugin. Preserve existing fencing rather than add @Version to every entity. Needs direct two-writer and ABA tests. |
| Metrics / load-test basis | EXISTING | Actuator + Micrometer Prometheus and existing benchmark harness. New formal benchmark is prohibited; QPS/latency/error figures must not be invented. |
| Tests | EXISTING | JUnit 5/Mockito/H2/MockMvc/Testcontainers for PostgreSQL, RabbitMQ, Redis, Elasticsearch; chat/Agent/SSE/citation/IDOR and MQ tests. Real smoke tests are conditional; archived tooling checks may require intentionally excluded files. Report those separately without weakening tests. |

## Current document pipeline and topology

Upload authorizes workspace owner, validates format/size/checksum, inserts UPLOADING, uploads MinIO, then transactionally marks PENDING and creates task; publish confirms mark QUEUED, otherwise dispatcher recovers. Consumer atomically claims PROCESSING, parses and chunks outside the transaction, then persists chunks/task SUCCEEDED/document COMPLETED plus retrieval PENDING in one transaction. Embedding/indexing is a **separate existing retrieval task**, not an ingestion state added for appearance.

Default document topology: `intellidesk.document.x` / `document.process` / `intellidesk.document.process.q`; retry `intellidesk.document.dlx` / `document.process.retry` / `intellidesk.document.process.retry.q`, 30000 ms TTL; dead letter `intellidesk.document.dlx` / `document.process.dead` / `intellidesk.document.process.dlq`. DB retryDelaySeconds defaults to 30. maxAttempts=3 is total attempts, not three additional retries. Recovery republishes unconfirmed pending/stale queued/due retry records; terminal records never restart processing.

## Ordered implementation plan

1. Reuse Result/Advice; sanitize request trace IDs, scoped MDC propagation to durable MQ work, add leakage and cleanup tests.
2. Keep current task/document enums and separate retrieval workflow; formalize allowed transitions and verify rollback/terminal protection.
3. Strengthen the existing retry exchange contract and DLQ tests; no second retry mechanism.
4. Validate message/task identity and duplicate/fencing boundaries with tests.
5. Harden and test the existing JWT contract, preserving DB-resolved authorities and cookie/API-Key behavior.
6. Test current permission + workspace authorization; no arbitrary role migration or changed user grants.
7. Add bounded metadata-only Cache Aside, checking authorization every read and invalidating after DB commit.
8. Evaluate real SQL/index coverage; keep existing good indexes; save EXPLAIN only against isolated test data.
9. Verify existing optimistic CAS with concurrent writers, not a blanket @Version retrofit.
10. Prepare non-generating endpoint measurements and document runnable workloads; do not execute formal benchmarks or claim performance.

Each phase requires its own regression result before the next. Full regression PASS is only claimed if a full applicable run actually passes.
