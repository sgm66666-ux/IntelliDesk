# Phase 8 Wave 1 — RAG Evaluation Report

> **Implementation-side record**（非 Independent Review，非 Final Archive）。
> 本报告如实记录 approved amendment v1.1 实施后的最终真实质量结果与所有限制。
> **Ready for Independent Review: YES** —— 见 §18 / §20。

## 1. Scope

本报告覆盖 Phase 8 Wave 1（RAG Evaluation）approved amendment v1.1 实施侧产出：

- Embedding dimensions 传播修复（AMENDMENT-A）
- Local real reranker gateway（AMENDMENT-B）
- 冻结语料库、冻结数据集与相关性判断
- 真实本地 provider 四模式检索：VECTOR / KEYWORD / HYBRID / HYBRID_RERANK
- 指标计算（Hit@K / Recall@K / MRR）
- 原始结果持久化、可复现性验证与报告

本 Wave **未**执行：Wave 2 benchmark（k6）不在范围；未新增任何 high-cardinality 自定义 Meter；未修改任何 production business 代码；未开始 Wave 3 / 4 / Phase 9。

## 2. Corpus description

- **类型**：synthetic / controlled evaluation corpus（明确标注，非 production dataset、非 real enterprise benchmark）。
- **文档数**：14 个 `.md` source documents。
- **类别（domain）**：`dev`、`finance`、`hr`、`it`、`ops`、`travel`。
- **分块**：真实 `MarkdownDocumentParser` + `RecursiveChunkStrategy`（RECURSIVE, chunk_size=1000, chunk_overlap=150）→ 合计 **42 chunks**。
- **构成策略**：包含相似主题文档（hr-onboarding / it-security / it-vpn 等关键词重叠）、多 chunk 文档、需要语义匹配的问题、更适合关键词匹配的问题、multi-relevant-chunk 问题，并含一定 distractor 内容，使 Vector / BM25 / Hybrid 形成有意义对比。
- **无私有数据**：全部为受控合成内容（HR/IT/Finance/Ops/Travel 政策手册）。

### Corpus 元数据

| 字段 | 值 |
|------|-----|
| document count | 14 |
| categories | dev / finance / hr / it / ops / travel |
| provenance | synthetic-controlled-evaluation-corpus |
| duplicate policy | no-duplicate-source-files（内容级重叠仅作为 distractor 出现） |
| chunk count | 42 |
| chunk config | RECURSIVE 1000 / 150 |
| corpus version | `20260821-eval-v1` |
| no private data | true |

### Hashes（source-bound freeze，v2.2 model）

| 分量 | 值 |
|------|-----|
| `source_content_hash` | `87f97e31d973d8ff7129ad483b5e84d45b82bd407194d699e2216a363f478462` |
| `parser_chunk_config_hash` | `a22d6b8f458a738bfc9e96579d7dba661078358c60a5b26d39ed57c3e01cc5a8` |
| `indexed_chunk_manifest_hash` | `9f12a01ae0ae70f27d88bc38d362e8b09506bad49af7ecd033b48823124eb6ee` |
| **`corpus_hash`** | `4230806359a3538a5fd166025d7344a8c302b4badb5a91611d6fe1b67adac108` |

- 单一来源文档 digest 基于 **SHA-256 of raw source bytes**（`content_sha256`，见 `source_manifest.json`）。
- canonical manifest：UTF-8、stable field order、按 `relative_path` 排序、路径统一 `/`、**不含 mtime**。
- byte-change 验证：对临时副本改动任意 1 byte → 该文档 `content_sha256` 变 → `source_content_hash` 变 → `corpus_hash` 变；验证后恢复原文件，未污染正式 frozen corpus（详见 `scripts/evaluation/freeze_corpus.py --verify-byte-change`）。

## 3. Corpus provenance

全部文件由本推进的受控合成内容生成，`provenance= synthetic-controlled-evaluation-corpus`。清单见 `docs/evaluation/corpus/source_manifest.json`（14 entries）。

## 4. Synthetic / controlled disclosure

明确披露：本语料为 **synthetic / controlled evaluation corpus**，**不是** production dataset，也**不是** real enterprise benchmark。请勿据此宣称真实业务语义检索能力。

## 5. Corpus hash

见 §2 表：`corpus_hash = 42308063…`。

## 6. Dataset description

`docs/evaluation/dataset/evaluation_dataset.json`（frozen evaluation set），字段：`id`、`question`、`relevant_documents[]`、`relevant_chunks[]`、`reference_answer`、`relevance_notes`、`qualified_quality_question`。

- **total**：69
- **quality（qualified）**：60（`qualified_quality_question=true` 且 ≥1 relevant chunk）
- **adversarial / no-relevant**：9（`qualified_quality_question=false`，0 relevant chunk，单列不进入合格基数）
- **dev / calibration**：20（`dev_calibration.json`，与 final set 无重叠；用于未来参数校准，本轮未做 final-set tuning）
- **stable identity**：document 用 logical id（如 `it-security-policy`），chunk 用 `logicalDocumentId|ordinal`（如 `it-security-policy|0`），与 `indexed_chunk_manifest.json` 一一对应，**不依赖 DB 自增 ID**。

### Dataset hash

```
dataset_hash = 919d5383f7e64635c7a7ad8c46cc9964658f92d3ff4fce702c1f25ba6c5d8baf
```

## 7. Dataset hash

见 §6。

## 8. Retrieval config / hash

`docs/evaluation/config/retrieval_config.json`（approved amendment v1.1 后冻结）：

```
candidate_top_k = 50
top_k          = 10
reported_k     = [1, 3, 5, 10]
rrf_k          = 60
chunking       = RECURSIVE 1000 / 150
es_index       = intellidesk-chunks-v1
index_generation = 1

embedding:
  provider_mode = LOCAL_OLLAMA_REAL
  model         = qwen3-embedding:8b
  dimension     = 1536

reranker_quality_config:
  provider_mode                 = LOCAL_REAL_GATEWAY
  model_id                      = BAAI/bge-reranker-v2-m3
  model_revision_identity       = 953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e
  model_artifact_identity       = 9d44af6ea8ed4ee7a3313afdc384ed262d05a23435202827a6df4c7addc65b84
  tokenizer_artifact_identity   = same as model_artifact_identity (9d44af6ea8ed4ee7a3313afdc384ed262d05a23435202827a6df4c7addc65b84)
  inference_stack               = FlagEmbedding::FlagReranker
  inference_stack_version_identity = 5adc7ac0f44117daeab541814315c9e5403dd7a3acf76464d3c86c168eb14caf
  input_pair_semantics          = [query, document], no instruction, no prompt augmentation
  max_length                    = 512
  truncation_policy             = LEFT_TRUNCATE_QUERY_RIGHT_TRUNCATE_DOCUMENT_OR_ERROR
  normalization                 = false
  score_semantics               = raw cross-encoder relevance logit, higher = more relevant
  ranking_direction             = DESCENDING
  tie_break_policy              = PRESERVE_ORIGINAL_HYBRID_CANDIDATE_ORDER
  precision                     = FP32
  inference_mode                = model.eval() + torch.no_grad() / inference_mode

reranker_quality_config_hash  = 9cd3832be43b467c9591d9c4d94ebceb3cb851ca85210e98383aca94895c7f11
retrieval_config_hash         = 2ae529f6aa1ba50845890374762191cc8a41543aec3c1eca37f49a0e7cc847a7
```

### Config hash linkage

- `retrieval_config_hash` 由 `EvalHashing.canonicalJson` + `sha256Hex` 单一权威序列化/哈希计算：
  - `embedding`（provider_mode / model / dimension）
  - `reranker_quality_config_hash`
  - `retrieval_params`（candidate_top_k / top_k / reported_k / rrf_k / es_index / index_generation）
- `RagEvaluationHarness.buildConfigBlock()` 与 `EvalHashingTest.retrievalConfigHashMatchesFrozenArtifact()` 共用同一逻辑；runner、freeze artifact、raw `config_hash` 三者一致。
- 所有 8 个 raw run 的 `config_hash` 均为 `2ae529f6…`，与 frozen `retrieval_config_hash` 完全一致。

## 9. Ground truth methodology

- Relevance judgments 由受控 corpus 内容人工编写（每题标注支持答案的 document/chunk，允许 multi-relevant）。
- eligible 定义：`qualified_quality_question=true` 且 truth 中至少 1 个 relevant chunk。
- 无 relevant chunk 的 adversarial / no-answer 题单列，不进入 mean Hit@K / Recall@K / MRR 基数。
- 所有最终质量路径均走真实 production retrieval 类与真实本地 provider；未使用 deterministic stub 关闭 quality gate。

## 10. No-test-set-tuning policy

- 参数（topK=10、candidateTopK=50、RRF k=60、chunking、embedding 配置、rerank 配置）在 final run 前冻结。
- 未根据 final 结果调整任何参数、ground truth 或 corpus。
- dev_calibration（20 题）与 final set（69 题）无重叠；本轮未做 tuning。
- 报告 K 集合（1/3/5/10）在 final run 前冻结，未看结果挑最漂亮 K。

## 11. Metric definitions

对 eligible quality question `q`、top-K 检索结果 `Ret_K(q)`、全量排序 `R(q)`、冻结 relevant set `Rel(q)`：

```
Hit@K(q)   = 1  if Ret_K(q) ∩ Rel(q) ≠ ∅  else 0
Recall@K(q)= |Ret_K(q) ∩ Rel(q)| / |Rel(q)|
MRR(q)     = 1 / rank(first relevant in R(q));  top-K 无 relevant → 0
```

- **chunk-level** 与 **document-level** 分开聚合，不混为一个数。
- 聚合 = mean over eligible quality questions（60）；无命中但 qualified 的题纳入基数（贡献 0）。
- 原始证据同时保存：rank、logicalDocumentId、logicalChunkId、score、score_type、relevant true/false（raw 中已包含）。

## 12-15. Final Real Quality Results（4 modes，per-mode）

真实 provider：

- Embedding：Ollama 0.32.5，`qwen3-embedding:8b`，`dimensions=1536`
- BM25：Elasticsearch 8.17.10 + analysis-smartcn
- Reranker：Local gateway `127.0.0.1:18182` → `BAAI/bge-reranker-v2-m3` via `FlagEmbedding::FlagReranker`

合格基数：eligible quality = **60**。

### Chunk-level

| Mode | Hit@1 | Hit@3 | Hit@5 | Hit@10 | Recall@10 | MRR |
|------|------|------|------|-------|-----------|------|
| VECTOR | 0.78333 | 0.98333 | 1.00000 | 1.00000 | 1.00000 | 0.87833 |
| KEYWORD (BM25) | 0.86667 | 1.00000 | 1.00000 | 1.00000 | 1.00000 | 0.93056 |
| HYBRID | 0.83333 | 1.00000 | 1.00000 | 1.00000 | 1.00000 | 0.91111 |
| HYBRID_RERANK | 0.16667 | 0.51667 | 0.63333 | 1.00000 | 1.00000 | 0.38493 |

### Document-level

| Mode | Hit@1 | Hit@3 | Hit@5 | Hit@10 | Recall@10 | MRR |
|------|------|------|------|-------|-----------|------|
| VECTOR | 0.88333 | 1.00000 | 1.00000 | 1.00000 | 1.00000 | 0.94167 |
| KEYWORD (BM25) | 0.98333 | 1.00000 | 1.00000 | 1.00000 | 1.00000 | 0.99167 |
| HYBRID | 0.95000 | 1.00000 | 1.00000 | 1.00000 | 1.00000 | 0.97500 |
| HYBRID_RERANK | 0.45000 | 0.95000 | 1.00000 | 1.00000 | 1.00000 | 0.67639 |

> 数字来源：`docs/evaluation/derived/metrics_summary.json`（mode × run × level × K）。recompute 路径只读 raw，不做检索重放，确定性。

## 15. Results note on HYBRID_RERANK

- `eval-HYBRID_RERANK-{1,2}.json` 中 `rerank_executed=true`、`score_type=reranked`，真实 cross-encoder 已执行。
- 在本受控语料上，HYBRID_RERANK 的 top-K 指标低于 HYBRID（例如 chunk Hit@1 0.16667 vs 0.83333）。该结果如实记录，未因分数差而调参、换模型或重跑。
- 原因分析：BGE reranker 对短政策问答片段的排序与冻结的 HYBRID 候选顺序存在差异；本次仅验证真实 reranker 可按 frozen canonical config 执行并产出 finite scores，不宣称通用业务增益。

## 16. Reproducibility

| Mode | run1 vs run2（ranked identity + order + score + type + relevant） |
|------|------------------------------------------------------------------|
| VECTOR | IDENTICAL |
| KEYWORD | IDENTICAL |
| HYBRID | IDENTICAL |
| HYBRID_RERANK | IDENTICAL（本次 CPU FP32 执行未出现 variation；已如实记录） |

- VECTOR / KEYWORD / HYBRID 为确定性检索，两次运行必须一致。
- HYBRID_RERANK 使用真实 cross-encoder；本次运行两次输出一致，但仍按 nondeterministic 路径记录，未据此声称全局确定性。

## 17. Per-mode comparison

- **KEYWORD（BM25）整体最强**：本语料以事实型/数字型政策问答为主，关键词高度重叠，BM25 chunk MRR=0.93056、Hit@10=1.0。
- **VECTOR（真实 Ollama embedding）表现良好**：chunk Hit@10=1.0、MRR=0.87833，但 Hit@1 低于 BM25/HYBRID。
- **HYBRID 接近 BM25**：RRF 融合在多数 K 上达到 Hit@10=1.0，MRR=0.91111。
- **HYBRID_RERANK 本次低于 HYBRID**：真实 reranker 重排后 top-K 命中下降；如实记录，不粉饰。

> 结论：在本受控合成语料与所选真实模型组合下，BM25 ≈ HYBRID > VECTOR > HYBRID_RERANK（按 top-K 命中）。该结论受语料特性与 reranker 域适配影响，仅限本 evaluation corpus。

## 18. Limitations / honesty

| 项 | 说明 |
|----|------|
| **Historical synthetic raw** | 本次最终 real-quality run 前，`docs/evaluation/raw/` 中已存在同名的中间 run 文件。`RagEvaluationHarness` 按原设计写入同名路径，因此历史 synthetic raw 的机器可读文件已被覆盖。历史 synthetic 聚合指标保留于本报告 Appendix A；最终 real raw 为本目录当前文件。 |
| **HYBRID_RERANK 结果偏低** | 真实 reranker 在 top-K 早期位置降低了命中，已如实记录，未调参或删除差结果。 |
| **Local-only providers** | Embedding / reranker 均绑定本机 Ollama / localhost gateway，非 production 托管服务； Independent Review 需在同等本地环境复跑。 |

## 19. Interpretation

- 本 Wave 交付可复现的 RAG evaluation harness + 冻结语料/数据集/配置 + 真实容器化 production retrieval 类 + 真实本地 embedding/reranker provider。
- 受控合成语料上，关键词类事实问答由 BM25 主导；真实向量语义质量可用；真实 reranker 本次未带来增益，已如实记录。
- 所有数字均可从 raw 用 `EvalRecomputeScript` 独立重算，无 cherry-picking、无手工改 raw、无删除差题、无改 ground truth。

## 20. Raw artifact paths

- Raw runs：`docs/evaluation/raw/eval-VECTOR-{1,2}.json`、`eval-KEYWORD-{1,2}.json`、`eval-HYBRID-{1,2}.json`、`eval-HYBRID_RERANK-{1,2}.json`
  - 每条包含：run_id、timestamp、mode、corpus_hash、dataset_hash、config_hash、topK、candidateTopK、reported_k、rerank_config、embedding、index、harness_file_sha256、environment、per-question `ranked[]`（rank/db_chunk_id/logical_document_id/logical_chunk_id/score/score_type/relevant）与 `retrieval_latency_ms`。
  - HYBRID_RERANK raw 中 `rerank_executed=true`，`reranker_metadata` 绑定 model/artifact/tokenizer/inference-stack identity。
  - **敏感数据扫描**：raw 未包含任何 provider secret / Authorization / access token / refresh token / API key；question 为受控合成非敏感文本。
- Chunk manifest：`docs/evaluation/raw/indexed_chunk_manifest.json`
- Derived metrics：`docs/evaluation/derived/metrics_summary.json`
- Corpus：`docs/evaluation/corpus/`（14 .md + source_manifest.json + corpus_freeze.json）
- Dataset：`docs/evaluation/dataset/evaluation_dataset.json`（69 题）、`dev_calibration.json`（20 题）、`dataset_freeze.json`
- Config：`docs/evaluation/config/retrieval_config.json`
- Reranker identity artifacts：`scripts/evaluation/local-reranker/identity/`
- Harness：`backend/src/test/java/com/intellidesk/evaluation/RagEvaluationHarness.java`
- Recompute：`backend/src/test/java/com/intellidesk/evaluation/EvalRecomputeScript.java`
- Corpus freeze script：`scripts/evaluation/freeze_corpus.py`

## Appendix A — Historical Pipeline Validation（SYNTHETIC）

以下数字来自 amendment v1.1 实施前的 synthetic / pipeline-validation run，仅用于证明 harness 链路走通，**不构成最终真实质量证据**。对应机器可读 raw 文件已被最终 real run 覆盖。

### Chunk-level（synthetic）

| Mode | Hit@1 | Hit@3 | Hit@5 | Hit@10 | Recall@10 | MRR |
|------|------|------|------|-------|-----------|------|
| VECTOR (synthetic) | 0.01667 | 0.10000 | 0.15000 | 0.28333 | 0.28333 | 0.07842 |
| KEYWORD (BM25) | 0.86667 | 1.00000 | 1.00000 | 1.00000 | 1.00000 | 0.93056 |
| HYBRID | 0.20000 | 0.46667 | 0.56667 | 0.73333 | 0.72500 | 0.36372 |
| HYBRID_RERANK (identity, no real rerank) | 0.20000 | 0.46667 | 0.56667 | 0.73333 | 0.72500 | 0.36372 |

### Document-level（synthetic）

| Mode | Hit@1 | Hit@3 | Hit@5 | Hit@10 | Recall@10 | MRR |
|------|------|------|------|-------|-----------|------|
| VECTOR (synthetic) | 0.01667 | 0.25000 | 0.35000 | 0.56667 | 0.55833 | 0.16980 |
| KEYWORD (BM25) | 0.98333 | 1.00000 | 1.00000 | 1.00000 | 1.00000 | 0.99167 |
| HYBRID | 0.40000 | 0.75000 | 0.88333 | 0.98333 | 0.98333 | 0.60893 |
| HYBRID_RERANK (identity, no real rerank) | 0.40000 | 0.75000 | 0.88333 | 0.98333 | 0.98333 | 0.60893 |

## Appendix B — Real Provider Runtime Identity

| 组件 | 身份 |
|------|------|
| Ollama version | 0.32.5 |
| Embedding model | qwen3-embedding:8b |
| Embedding dimension | 1536 |
| Embedding base URL | http://127.0.0.1:11434 |
| ES version | 8.17.10 + analysis-smartcn |
| Reranker gateway | 127.0.0.1:18182 |
| Reranker model | BAAI/bge-reranker-v2-m3 |
| Model revision | 953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e |
| Model artifact identity | 9d44af6ea8ed4ee7a3313afdc384ed262d05a23435202827a6df4c7addc65b84 |
| Tokenizer artifact identity | same as model artifact identity |
| Inference stack | FlagEmbedding::FlagReranker 1.2.11 |
| torch | 2.11.0+cpu |
| transformers | 4.51.3 |
| Python | 3.12.2 |
| Precision | FP32 (`torch.float32`) |
| Inference mode | `model.eval()` + `torch.no_grad()` + `torch.inference_mode()` |
| Max length | 512 |
| Truncation | LEFT_TRUNCATE_QUERY_RIGHT_TRUNCATE_DOCUMENT_OR_ERROR |
| Normalization | false |
| Ranking | DESCENDING，tie-break = preserve original HYBRID order |

## 21. Output-Integrity Implementation — Evidence-Loss Amendment v1.1 (2026-08-22)

本节记录 approved amendment v1.1 的 output-integrity / evidence-loss compensation 实施结果。实施角色：`implementation`；run-set-id：`implementation-compensation-001`；状态：已 atomic finalize 为 `COMPLETE`，等待 Independent Wave 1 Review。

### 21.1 Pre-change final real raw baseline

在修改 harness 前，对 `docs/evaluation/raw/` 中 8 个 final real raw 建立 baseline manifest：

- manifest 路径：`docs/evaluation/raw/final_real_raw_manifest.json`
- manifest hash：`da169ca025ea574ae28152280cd64d135433e6e9ffcfd70f11d3675a56afb9a0`
- 8 个文件：eval-VECTOR-{1,2}.json、eval-KEYWORD-{1,2}.json、eval-HYBRID-{1,2}.json、eval-HYBRID_RERANK-{1,2}.json
- 每个 entry 仅含 `relative_path`、`size_bytes`、`sha256`；UTF-8 canonical、字段名/顺序稳定、`/` 分隔符、按 relative_path 升序、不含 mtime/PID/inode/absolute path/random/timestamp。
- baseline 建立后未移动/重写/格式化上述 8 个 raw。

### 21.2 Post-change byte-identical verification

修改 harness 后，重新计算原 8 个 final real raw 的 size_bytes / sha256，与 baseline 逐文件比较：

- 结果：**8/8 byte-identical**
- 验证脚本：`scripts/evaluation/verify_baseline.py`
- `FINAL_REAL_RAW_PROTECTED = YES`

### 21.3 Immutable run-set namespace

- evidence class：`real-quality`
- frozen retrieval_config_hash：`2ae529f6aa1ba50845890374762191cc8a41543aec3c1eca37f49a0e7cc847a7`
- canonical config-id：`2ae529f6aa1ba508`（full hash 的前 16 位小写十六进制）
- run-set-id：`implementation-compensation-001`（格式 `<actor>-<purpose>-<sequence>`）
- run-set 路径：`docs/evaluation/raw/real-quality/2ae529f6aa1ba508/implementation-compensation-001/`
- `FAIL_IF_EXISTS`：已验证；如目录已存在则抛出 `IllegalStateException`。
- 文件写入：临时文件 + flush/close + atomic rename；目标存在时失败。
- lifecycle：`INITIALIZING → PARTIAL → COMPLETE`；crash/error 时保留为 `PARTIAL` 或 `FAILED`；只有全部 mandatory criteria 通过后才 atomic finalize 为 `COMPLETE`。

### 21.4 Complete four-mode final-real replay

在新 run-set namespace 下执行完整 four-mode final-real replay：

| Mode | Run 1 | Run 2 | 路径 |
|------|-------|-------|------|
| VECTOR | ✓ | ✓ | `.../eval-VECTOR-{1,2}.json` |
| KEYWORD (BM25) | ✓ | ✓ | `.../eval-KEYWORD-{1,2}.json` |
| HYBRID | ✓ | ✓ | `.../eval-HYBRID-{1,2}.json` |
| HYBRID_RERANK | ✓ | ✓ | `.../eval-HYBRID_RERANK-{1,2}.json` |

- corpus frozen、dataset frozen、relevance judgments frozen、config frozen。
- 未做 test-set tuning；未调 topK/candidateTopK/RRF/embedding/reranker/corpus/dataset/judgments。
- HYBRID_RERANK raw 中 `rerank_executed=true`，真实 cross-encoder 已执行。
- run1 vs run2 reproducibility：VECTOR / KEYWORD / HYBRID / HYBRID_RERANK 均为 `IDENTICAL`（本次 CPU FP32 未出现 variation，仍按真实 reranker nondeterministic 路径记录）。

### 21.5 Provider verification

- Embedding：真实 local Ollama 0.32.5，`qwen3-embedding:8b`，`dimensions=1536`，`SpringAiEmbeddingService` 验证通过。
- Reranker：真实 local gateway `127.0.0.1:18182` → `BAAI/bge-reranker-v2-m3` via `FlagEmbedding::FlagReranker`；production 路径 `RerankService → RerankClient → DashScopeCompatibleRerankClient → local real gateway` 保留。
- model/revision/tokenizer/inference-stack identity 与 frozen canonical reranker quality config 一致。

### 21.6 Raw-only recompute

- 从新的 replay raw 单独执行 recompute：`EvalRecomputeScript.recompute(runSetDir, runSetSummary, datasetFile)`
- 输出 `metrics_summary.json`；另生成独立 recompute `metrics_summary_independent_recompute.json`。
- 比较结果：`metrics_summary.json == metrics_summary_independent_recompute.json`（忽略 `derived_from`）。
- 指标：Hit@K、Recall@K、MRR；chunk-level / document-level 分开。

### 21.7 Run-set manifest

- 路径：`docs/evaluation/raw/real-quality/2ae529f6aa1ba508/implementation-compensation-001/run_set_manifest.json`
- 状态：`COMPLETE`
- 记录字段：schema_version、evidence_class、config_hash、config_id、corpus_hash、dataset_hash、run_set_id、actor、purpose、expected_modes、expected_runs_per_mode、status、created_at、completed_at、completed_modes、completed_runs_per_mode、evidence_entries（relative_path / size_bytes / sha256）。
- canonical evidence identity 不依赖 mtime/PID/inode/absolute path/random。

### 21.8 Historical synthetic raw loss disclosure

- 原始 machine-readable synthetic raw 已被覆盖且**不可恢复**（`IRRECOVERABLY_LOST`）。
- 未重新生成 synthetic raw 冒充 original；Appendix A 继续标记为 summary-only historical synthetic pipeline-validation evidence。
- 新 immutable replay evidence 位于 `docs/evaluation/raw/real-quality/...`。

## Ready for Independent Review: YES

- 14 个 Wave 1 mandatory gates 全部实施并 PASS（见 implementation record gate matrix）。
- Amendment implementation gates 全部 PASS。
- Backend / frontend 回归通过（见 implementation record）。
- 真实 embedding / BM25 / HYBRID / HYBRID_RERANK 均经 production path 执行并持久化 raw。
- Config hash linkage 一致（runner == freeze artifact == raw == run-set manifest）。
- 原始 8 个 final real raw 已受 baseline manifest 保护且 post-change byte-identical 验证通过。
- 新的 immutable run-set replay 已完成并 atomic finalize 为 COMPLETE。
- 限制已在 §18 / §21.8 如实披露（historical synthetic raw 被覆盖、HYBRID_RERANK 结果偏低）。
