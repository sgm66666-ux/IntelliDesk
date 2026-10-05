# Non-LLM backend performance preparation

No load test was executed and no performance numbers are claimed. This is not a formal benchmark, a repeatability run, or an update to historical benchmark methodology. The initial target is existing authorized metadata GETs, not RAG answers or Agent generation.

| Target | Existing API | Main backend work |
| --- | --- | --- |
| kb-detail | GET /api/workspaces/{workspaceId}/knowledge-bases/{knowledgeBaseId} | JWT, database authorities/membership, Redis metadata Cache Aside |
| document-list | GET .../knowledge-bases/{knowledgeBaseId}/documents?page=1&size=20 | Authorization, count + indexed paged SQL |
| document-status | GET .../documents/{documentId} | Document metadata and latest durable task status |

Upload is not in the initial read-only script: storage writes and asynchronous parsing require dedicated disposable fixtures and separate entrypoint latency vs completion measurements. Retrieval is likewise not an initial target: Embedding/search infrastructure must be independently characterized; frozen retrieval experiments remain stopped.

## Operator checklist (future execution, explicit authorization required)

1. Use an isolated disposable deployment and synthetic authorized fixtures. Do not target the enterprise corpus, production data, or frozen run-sets.
2. Supply BACKEND_BASE_URL, BACKEND_WORKSPACE_ID, BACKEND_KB_ID, optionally BACKEND_DOCUMENT_ID, BACKEND_TARGET, and BACKEND_ACCESS_TOKEN privately through process environment. Never put tokens in a tracked file or command transcript. Check validity/permission first without changing permissions to force success.
3. Verify k6 path/version. The script defaults to 1 VU and 10 requests, and rejects more than 10 VUs or 1,000 requests. Do not treat these preparation limits as an approved formal load profile.
4. When authorized, run `k6 run scripts/backend/non_llm_http.js`; do not automatically create a historical formal run-set. No request bodies, response bodies, or credentials are exported by this script. Do not enable HTTP debug logging.
5. Define cold vs warm cache and exclude a declared warmup before a real measurement; this preparation does not pretend its 10 iterations meet that contract. Keep independent run boundaries, complete error samples, and no selective retries.
6. Record client requests/sec (QPS for these GETs), complete sample count, P50/P95/P99 and error rate; native k6 http_req_duration measures sending + waiting + receiving, not connection queue/setup. Separately record blocked/connecting/TLS times and client CPU/memory. Do not infer end-to-end task latency from HTTP 202 upload latency.

Existing Actuator/Micrometer already emits http.server.requests histograms and Prometheus metrics on the internal backend port; public Nginx does not expose actuator. Server RPS: `sum(rate(http_server_requests_seconds_count[1m]))`; server P95: `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[1m])))`. Filter URI/status appropriately; P50/P99 use 0.50/0.99. Error rate divides non-success response counts by all responses, and must account for authenticated 401/403 rather than hide them. Histograms approximate quantiles; k6 sample quantiles are different observations, not interchangeable numbers.

Inspect actually exposed JVM CPU/memory/GC, Hikari connection, servlet thread, Redis and RabbitMQ metrics before promising their availability. The current code adds no new public actuator exposure, timer with secret/high-cardinality labels, SLA threshold, or model configuration.

Offline script validation: `node --test scripts/backend/non_llm_http.test.mjs` stubs k6 HTTP and tests bounded configuration, allowed paths, and fail-closed inputs. It sends zero HTTP requests.
