# Database access review

PostgreSQL + MyBatis-Plus, not JPA or MySQL. This review uses a disposable pgvector/PostgreSQL 16 Testcontainer, Flyway migrations, 20,000 synthetic documents/tasks, 1,000 processing tasks and 50 expired leases. No enterprise corpus, model calls, retrieval evaluation, or production database was used.

## Existing paths

| Query | Existing support | Decision |
| --- | --- | --- |
| KB workspace filter + created_at order | idx_kb_workspace_created | Keep; observed ordered index scan |
| Document KB filter + created_at order | idx_document_kb_created | Keep; observed ordered index scan |
| Task document history | idx_task_document_created | Keep |
| Task due retries | idx_task_dispatch(status,next_retry_at,created_at) | Keep; retry deadline differs from lease deadline |
| User roles / role permissions | unique relation pairs and existing FK-prefix indexes | No new index; tiny seeded role table may correctly use sequential scan |
| Active task / message / chunk identity | existing partial active-task uniqueness / UUID uniqueness / document+chunk_index uniqueness | Keep; real duplicate active task rejected |

Existing indexes whose prefixes overlap uniqueness indexes are not automatically dropped: this requires deployed workload and write-cost evidence. Leading-wildcard name LIKE and very deep OFFSET can still be expensive; no unrequested search semantics or keyset-pagination change is introduced.

## One new index

Migration `V7__document_task_lease_index.sql`: `document_index_task(status, lease_until)`.

Actual recovery query:

```sql
SELECT * FROM document_index_task
WHERE status = 'PROCESSING' AND lease_until < CURRENT_TIMESTAMP
ORDER BY lease_until LIMIT 20;
```

Equality first, then deadline range/order. The existing dispatch index orders by next_retry_at, not lease_until. Before the new index, the captured plan scans the active-task index, filters non-expired leases, and sorts. Afterwards PostgreSQL uses idx_task_status_lease for the equality/range and ordered limit, without a separate sort. SELECT * still visits heap tuples: this is not an index-only scan. Task insertion, status changes, and lease updates maintain another B-tree; this is a deliberate write/storage cost, not a free optimization.

Exact EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) plans are in [sql-plans.json](sql-plans.json). Single-run timings are diagnostic observations on synthetic data, **not** a throughput benchmark, speedup guarantee, production SLA, or evidence of high concurrency.

`DocumentSqlPlanIntegrationTest` recreates pre/post migration plans and verifies active-task uniqueness. V1–V5 migrations are unchanged; no deployed database migration has been run by this task.
