# IntelliDesk Phase 8 Wave 2 Benchmark Report

Date: 2026-09-10  
Scope: seven mandatory Wave 2 benchmark scenarios  
Status: `PHASE8_WAVE2_FINAL_PASS_SELF_REVIEWED`

## Executive summary

All seven mandatory scenarios have a distinct finalized run-set and pass the read-only strict acceptance validator. Across the accepted run-sets there are 1,691,143 authoritative measured samples/executions and zero authoritative errors. No failed, diagnostic, superseded, or historical run-set is pooled into these results.

These are controlled local benchmark results. They are useful for regression baselines and engineering comparison, but they are not production SLAs. Deterministic provider-boundary stubs are identified explicitly; real-provider latency is not inferred from them.

## Accepted run-set matrix

| Scenario | Contract / provider | Accepted run-set | Matrix | Authoritative samples | Errors | Strict acceptance |
|---|---|---|---:|---:|---:|---|
| Retrieval | COMPONENT / real retrieval; real reranker provider path | `b-formal-retrieval-20260902-008` | 4 modes x c1/c4/c8 x 3 runs = 36 cells | 28,157 | 0 | eligible=true |
| API Key Auth | COMPONENT / real prefix lookup + BCrypt | `a-formal-api-key-auth-004` | c1/c4/c8 x 3 runs = 9 cells | 9,000 | 0 | eligible=true |
| RAG Pipeline / SSE TTFT | EXTERNAL_K6 / deterministic provider stub, real system pipeline | `a-formal-rag-pipeline-010` | VU1/5/10 x 3 runs = 9 cells | 6,042 | 0 | eligible=true |
| HTTP Concurrency | EXTERNAL_K6 / real local HTTP path | `a-formal-http-concurrency-010` | VU1/5/10 x 3 runs = 9 cells | 1,647,704 | 0 | eligible=true |
| RAG Completion | B_CLASS / deterministic provider stub, real orchestration pipeline | `b-formal-rag-completion-20260910-001` | 3 x 30 measured; 1 discarded warmup/run | 90 | 0 | eligible=true |
| Document Processing | B_CLASS / real infrastructure and indexing path | `b-formal-document-processing-20260910-002` | 3 x 20; no warmup | 60 | 0 | eligible=true |
| Agent Tool Flow | B_CLASS / deterministic provider stub, real agent/tool pipeline | `b-formal-agent-tool-flow-20260910-001` | 3 x 30 measured; 1 discarded warmup/run | 90 | 0 | eligible=true |

## Authoritative latency results

All values below are recomputed/validated from persisted authoritative raw records using offline nearest-rank percentiles. k6 summaries are cross-checks only.

### Retrieval component

VECTOR, BM25, and HYBRID each contributed 27,000 standard-contract samples in total; real RERANK contributed 1,157 provider-class samples. All 12 mode/concurrency groups passed component `CV(p50)<=10%` and p95 median-relative deviation `<=15%`.

Representative real-RERANK observations:

| Group | p50 range across runs (ms) | p95 range across runs (ms) |
|---|---:|---:|
| RERANK-c1 | 5,485.912–5,498.814 | 5,604.360–5,627.609 |
| RERANK-c4 | 17,684.238–17,843.756 | 19,696.955–20,099.918 |
| RERANK-c8 | 32,011.400–32,187.930 | 33,858.142–34,257.129 |

RERANK p99 is descriptive only because its provider-class sample volume is intentionally lower than the standard modes.

### API Key authentication component

| Concurrency | p95 values across runs (ms) | p95 range (ms) |
|---:|---|---:|
| 1 | 56.0315 / 53.7327 / 53.3792 | 53.3792–56.0315 |
| 4 | 60.5889 / 60.6636 / 62.7716 | 60.5889–62.7716 |
| 8 | 70.6547 / 72.5663 / 70.8619 | 70.6547–72.5663 |

This component measurement captures the real authentication-filter prefix-lookup and BCrypt path. It is an observed component cost, not a causal attribution of a paired HTTP difference.

### RAG pipeline TTFT

| VU | Sustain sample counts | p50 across runs (ms) | p95 across runs (ms) | Max p95 deviation |
|---:|---|---|---|---:|
| 1 | 123 / 123 / 122 | 31 / 31 / 31 | 32 / 32 / 32 | 0% |
| 5 | 622 / 625 / 633 | 31 / 31 / 31 | 32 / 32 / 32 | 0% |
| 10 | 1259 / 1267 / 1268 | 31 / 31 / 31 | 32 / 32 / 32 | 0% |

TTFT T1 is the first non-empty token event; start/citation/usage/done/error/empty-token events do not qualify. This measures the real system pipeline against a deterministic provider stub and does not claim real-provider latency.

### HTTP concurrency

| VU | p95 values across runs (ms) | Median p95 (ms) | Max p95 deviation |
|---:|---|---:|---:|
| 1 | 1.0482 / 1.0497 / 1.0514 | 1.0497 | 0.161951% |
| 5 | 1.2470 / 1.1364 / 1.1596 | 1.1596 | 7.537082% |
| 10 | 1.6362 / 1.6038 / 1.6295 | 1.6295 | 1.577171% |

All levels pass the frozen external `<=20%` p95 repeatability rule. The high-precision boundary is k6 response sending + waiting + receiving and excludes blocked/DNS/connect/TLS time; no pre-percentile quantization is applied.

### B-class scenarios

| Scenario | Run sample counts | p50 values (ms) | p95 values (ms) | Max p95 deviation |
|---|---|---|---|---:|
| RAG Completion | 30 / 30 / 30 | 216.5211 / 216.6030 / 216.6617 | 232.2026 / 229.7800 / 231.1156 | 0.577893% |
| Document Processing | 20 / 20 / 20 | 1858.9343 / 2060.8953 / 2059.0561 | 2079.8419 / 2068.7687 / 2064.9672 | 0.535256% |
| Agent Tool Flow | 30 / 30 / 30 | 215.6522 / 216.1287 / 215.6752 | 220.0485 / 229.5446 / 230.6432 | 4.136930% |

Document Processing includes the first cold execution because warmup is frozen at zero. Run 1 p99 is 5780.9126 ms and is retained; with N=20/run it is not presented as a high-confidence production tail SLA.

## Execution and evidence contracts

- Retrieval and API Key Auth satisfy component sample/time AND gates, concurrency levels, three independent runs, real path, cleanup, and config/hash linkage.
- RAG Pipeline and HTTP Concurrency use a 5-second ramp plus 30-second authoritative sustain phase at VU1/5/10 for three independent runs. Ramp and metadata records are excluded by the authoritative selector.
- RAG Completion and Agent Tool Flow use the real orchestration pipelines with deterministic provider-boundary stubs; each execution is serialized and subject to its frozen timeout.
- Document Processing uses PostgreSQL, MinIO, RabbitMQ, the document consumer, parser, chunking, embedding, and Elasticsearch indexing. T1 requires both `DocumentStatus.COMPLETED` and `RetrievalTaskStatus.READY`.
- Formal manifests are writer-owned and remain immutable. `COMPLETE` means artifact completeness; the separate read-only strict validator supplies final eligibility.

## Immutable evidence index

| Scenario | Manifest SHA-256 |
|---|---|
| Retrieval 008 | `895f10987cd3d02880df7ffc6a886df252af2aca8b8aedf543e957dc5a2e3e8a` |
| API Key Auth 004 | `0d8bae074bab8219b6932d16621109e3fdaeefd1a7b96db6b07f02ffdef12165` |
| RAG Pipeline 010 | `2186f86e38429a23992dde3263e550c69d0e9b05ac297d139e7613e6b8ef35d4` |
| HTTP Concurrency 010 | `aeb1c500f497d367f4dd936d91b8279df09313e00c6f8e42c81cdd414bbbf306` |
| RAG Completion 001 | `b48429d3cd8a74502f1583bed1c5fed720c2e36f1e59da4e6085c37f73e5ab28` |
| Document Processing 002 | `980b446684186b04d9d6e49899965ae7a30d61c3196396dac2a4bfe49af61173` |
| Agent Tool Flow 001 | `6ae65c3b33b63d755c7a6893e5952d4216cf00006f0da33fd23fe82dea7c6082` |

## Rejected and non-authoritative evidence

The following evidence remains retained, immutable, and excluded from final metrics:

- HTTP Concurrency 003: permanently `NON_AUTHORITATIVE_FOR_FINAL_GATE`; manifest `a298f1905f431cb26c3b768d6328b9ba098a1abf92bb01a3a90dc9baf51ea006`.
- HTTP Concurrency 008: `FAILED / INELIGIBLE / IMMUTABLE`; manifest `460efe7f549723637faa3f94d540326cf897a2008a15652c92f24502e7a27a32`.
- HTTP Concurrency 009: `FAILED / INELIGIBLE / IMMUTABLE`; manifest `0368ef0c8e94ec0ebe3113417df9291b022924c9b3bf65ad6dd4a6e1041ed04f`.
- Document Processing 001: `INELIGIBLE / IMMUTABLE` after repeated-source deduplication exposed a harness defect; manifest `bcecf19216c63c76dd3383ca29756861cb9f40120b95fcdd0be256fb6defecd5`.
- Other failed/superseded Retrieval and benchmark run-sets remain in their historical directories and are not pooled or promoted.

## Traceability

- Retrieval: `docs/reviews/phase-08-wave-02-formal-retrieval-008-acceptance-20260905.md`
- API Key Auth: `docs/reviews/phase-08-wave-02-formal-api-key-auth-004-acceptance-20260905.md`
- RAG Pipeline: `docs/reviews/phase-08-wave-02-formal-rag-pipeline-010-acceptance-20260910.md`
- HTTP Concurrency: `docs/reviews/phase-08-wave-02-formal-http-concurrency-010-acceptance-20260910.md`
- RAG Completion: `docs/reviews/phase-08-wave-02-formal-rag-completion-001-acceptance-20260910.md`
- Document Processing: `docs/reviews/phase-08-wave-02-formal-document-processing-002-acceptance-20260910.md`
- Agent Tool Flow: `docs/reviews/phase-08-wave-02-formal-agent-tool-flow-001-acceptance-20260910.md`

Final Wave 2 archival status is recorded in `docs/reviews/phase-08-wave-02-final-self-review-20260910.md` and `docs/reviews/phase-08-wave-02-final-archive-20260910.md`.
