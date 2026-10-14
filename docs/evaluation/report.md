# IntelliDesk RAG Evaluation Report

本报告保留受控合成语料上的最终 real-quality 检索结果、指标定义与实验条件。检索相关性指标不等于生成答案正确率，结果仅适用于本数据集与模型组合。

## Scope and dataset

- 语料为 **SYNTHETIC / FICTIONAL**，非真实企业数据或生产 Benchmark。
- 14 份 Markdown 来源文档，覆盖 dev、finance、hr、it、ops、travel。
- 真实 MarkdownDocumentParser + RecursiveChunkStrategy：RECURSIVE，chunk_size=1000、chunk_overlap=150，实际 42 个 Chunk。
- 冻结问题共 69 个：60 个有至少一个相关 Chunk 的合格质量问题；9 个无相关证据问题单列，不进入质量指标分母。
- 另有 20 个 dev/calibration 问题，与最终 69 题无重叠；未对最终测试集调参。
- Ground Truth 根据受控语料人工标注相关 document/chunk，允许多相关项。逻辑文档 ID 与 Chunk ordinal 标识证据，不依赖数据库自增 ID。
- 比较 VECTOR、KEYWORD（BM25）、HYBRID、HYBRID_RERANK。所有最终质量路径均使用真实系统检索类与真实本地 Provider。

## Experimental conditions

| 项目 | 冻结配置 |
| --- | --- |
| 候选与返回 | candidate_top_k=50、top_k=10；reported_k=[1,3,5,10] |
| 融合 | RRF k=60 |
| 索引 | intellidesk-chunks-v1，index_generation=1 |
| Embedding | 本地 Ollama 0.32.5；qwen3-embedding:8b；1536 维 |
| BM25 | Elasticsearch 8.17.10 + analysis-smartcn |
| Reranker | 本地真实 gateway；BAAI/bge-reranker-v2-m3；FlagEmbedding::FlagReranker 1.2.11 |
| 推理环境 | Python 3.12.2、torch 2.11.0+cpu、transformers 4.51.3、CPU FP32 |
| 重排输入 | [query, document]，无 instruction / prompt augmentation；max_length=512 |
| 截断与排序 | LEFT_TRUNCATE_QUERY_RIGHT_TRUNCATE_DOCUMENT_OR_ERROR；normalization=false；原始 cross-encoder logit 降序，同分保持 HYBRID 顺序 |
| 推理模式 | model.eval()、torch.no_grad()、torch.inference_mode() |

topK、candidateTopK、RRF、分块、Embedding、Reranker、语料与相关性标注在最终运行前冻结。未根据最终结果更换模型、调整参数、修改 Ground Truth 或挑选报告 K。

### Corpus identity

| 分量 | 值 |
|------|-----|
| `source_content_hash` | `87f97e31d973d8ff7129ad483b5e84d45b82bd407194d699e2216a363f478462` |
| `parser_chunk_config_hash` | `a22d6b8f458a738bfc9e96579d7dba661078358c60a5b26d39ed57c3e01cc5a8` |
| `indexed_chunk_manifest_hash` | `9f12a01ae0ae70f27d88bc38d362e8b09506bad49af7ecd033b48823124eb6ee` |
| **`corpus_hash`** | `4230806359a3538a5fd166025d7344a8c302b4badb5a91611d6fe1b67adac108` |

| 其他身份 | SHA-256 / revision |
| --- | --- |
| dataset_hash | `919d5383f7e64635c7a7ad8c46cc9964658f92d3ff4fce702c1f25ba6c5d8baf` |
| retrieval_config_hash | `2ae529f6aa1ba50845890374762191cc8a41543aec3c1eca37f49a0e7cc847a7` |
| reranker_quality_config_hash | `9cd3832be43b467c9591d9c4d94ebceb3cb851ca85210e98383aca94895c7f11` |
| Reranker revision | `953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e` |
| Model / tokenizer artifact identity | `9d44af6ea8ed4ee7a3313afdc384ed262d05a23435202827a6df4c7addc65b84` |

## Metric definitions

对 eligible quality question `q`、top-K 检索结果 `Ret_K(q)`、全量排序 `R(q)`、冻结 relevant set `Rel(q)`：

```
Hit@K(q)   = 1  if Ret_K(q) ∩ Rel(q) ≠ ∅  else 0
Recall@K(q)= |Ret_K(q) ∩ Rel(q)| / |Rel(q)|
RR(q)      = 1 / rank(first relevant in R(q));  R(q) 中无 relevant → 0
MRR        = mean RR(q) over eligible quality questions
```

- Hit@K / Recall@K 只计算前 K 项；RR 使用传给计算器的完整返回列表，与报告 K 无关。例如相关项在第 2 位、K=1 时，Hit@1=0，而 RR=1/2。
- 本轮 harness 的 `TOP_K=10`：MRR 的排名范围是最多 10 项的最终返回列表，而非 `candidate_top_k=50` 的候选池或整个语料库。Document-level 按 chunk 列表中文档首次出现顺序去重后计算排名。
- 定义与 `EvalMetricsCalculator` 及 `EvalMetricsCalculatorTest.hitBehindKIsNoHitButMrrIsFullListRank` 一致。
- **chunk-level** 与 **document-level** 分开聚合，不混为一个数。
- 聚合 = mean over eligible quality questions（60）；无命中但 qualified 的题纳入基数（贡献 0）。
- 原始证据同时保存：rank、logicalDocumentId、logicalChunkId、score、score_type、relevant true/false（raw 中已包含）。

## Final Real Quality Results（4 modes，per-mode）

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

> 历史数字来源：未随公开仓库提供的历史验证材料中的 `docs/evaluation/derived/metrics_summary.json`（mode × run × level × K）。取得匹配的 raw 与 dataset 后，recompute 路径只读 raw，不做检索重放，确定性。

## Comparison and limitations

- BM25 的 chunk MRR=0.93056，高于 HYBRID 的 0.91111 和 VECTOR 的 0.87833；语料中的事实、数字与关键词重叠会影响比较结果。
- HYBRID_RERANK 的 chunk Hit@1=0.16667，低于 HYBRID 的 0.83333；真实 cross-encoder 已执行，原结果未因指标较低而被删除或替换。
- Hit@10 和 Recall@10 不描述早期排序质量，也不能单独证明最终上下文足以回答问题。
- 不能从该结果推断 Hybrid + RRF 普遍优于其他检索方式，或 Reranker 普遍提升效果。
- 本评测不提供生成答案正确率、引用充分性或生产 SLA 结论。

## Reproducibility

| Mode | run1 vs run2（ranked identity + order + score + type + relevant） |
|------|------------------------------------------------------------------|
| VECTOR | IDENTICAL |
| KEYWORD | IDENTICAL |
| HYBRID | IDENTICAL |
| HYBRID_RERANK | IDENTICAL（本次 CPU FP32 执行未出现 variation；已如实记录） |

- VECTOR / KEYWORD / HYBRID 为确定性检索，两次运行必须一致。
- HYBRID_RERANK 使用真实 cross-encoder；本次运行两次输出一致，但仍按 nondeterministic 路径记录，未据此声称全局确定性。

## Public evidence and archival scope

公开仓库保留报告、[Evaluation harness 与指标实现](../../backend/src/test/java/com/intellidesk/evaluation/)以及 [冻结、校验和 Provider identity 脚本](../../scripts/evaluation/)。完整 corpus、dataset、retrieval config、raw、Chunk manifest、derived metrics 和验收记录属于历史验证材料，不随公开 clone 分发。

历史指标由 raw 重算。独立重算需取得匹配的 raw 与 dataset，并核对 corpus、dataset、config 和 run-set 身份；重放还需完整语料、冻结配置及相同 Provider 环境。公开方法源码不能替代这些原始证据。

早期 synthetic / pipeline-validation 的机器可读 raw 曾被覆盖且不可恢复，其历史汇总不能作为最终 real-quality 质量证据。最终真实质量结果与这一早期记录分开解读；完整实施及证据处理记录属于未随公开仓库提供的历史验证材料。
