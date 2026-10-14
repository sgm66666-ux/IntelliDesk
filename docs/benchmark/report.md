# IntelliDesk Benchmark Report

Date: 2026-09-10  
Scope: seven controlled local benchmark scenarios

## Executive summary

All seven reported scenarios have a distinct finalized run-set and pass the read-only strict acceptance validator. Across the accepted run-sets there are 1,691,143 authoritative measured samples/executions and zero authoritative errors. No failed, diagnostic, superseded, or historical run-set is pooled into these results.

These are controlled local benchmark results. They are useful for regression baselines and engineering comparison, but they are not production SLAs. Deterministic provider-boundary stubs are identified explicitly; real-provider latency is not inferred from them.

## Public evidence and archival scope

This report preserves the historical measurement results and experimental conditions. The public repository contains the methodology, representative results, benchmark harnesses under `backend/src/test/java/com/intellidesk/benchmark/`, and tooling under `scripts/benchmark/`. Full fixtures, frozen runtime configurations, raw samples, manifests, and acceptance reviews are historical validation materials not included in the public repository.

Independent recomputation requires the matching raw records, manifests, frozen configuration, and authoritative selectors; replay additionally requires the original fixtures, infrastructure, and provider setup. These archival inputs are not included in the public clone.

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

VECTOR, BM25, and HYBRID together contributed 27,000 standard-contract samples; real RERANK contributed 1,157 provider-class samples, giving 28,157 retrieval samples in total. All 12 mode/concurrency groups passed component `CV(p50)<=10%` and p95 median-relative deviation `<=15%`.

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

## Limitations and evidence scope

These are historical measurements from a controlled local environment, not a new regression result or a production SLA. RAG Pipeline, RAG Completion and Agent Tool Flow use deterministic provider-boundary stubs; their timings do not establish real-model generation latency or answer quality. Retrieval's real reranker measurements use a different provider-class sample contract from the standard retrieval modes.

Only the accepted run-sets in the matrix contribute to these metrics. Earlier failed, diagnostic, superseded and non-authoritative runs remain excluded; their full classifications, manifest identities and acceptance reviews are historical validation materials not included in the public repository. They were not replaced or pooled to change the reported results.

Specifically, HTTP Concurrency 003 was non-authoritative; HTTP Concurrency 008/009 were historical failed runs; Document Processing 001 was ineligible after repeated-source deduplication exposed a harness defect. None of these runs contributed to the final metrics.

The public repository provides the harnesses, validation tooling and representative results. Reproducing historical results requires the archived inputs and original runtime conditions; the reported acceptance status does not establish that every test in a fresh public clone passes.
