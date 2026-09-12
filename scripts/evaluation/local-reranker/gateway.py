#!/usr/bin/env python3
"""
Phase 8 Wave 1 — Local Real Reranker Gateway.

Evaluation-only DashScope-compatible rerank HTTP gateway.
Binds localhost:18182, invokes BAAI/bge-reranker-v2-m3 via
FlagEmbedding::FlagReranker under the frozen canonical reranker quality config.

NOT used in production runtime / Docker / frontend.
"""

from __future__ import annotations

import argparse
import json
import logging
import math
import os
import sys
from typing import Any

import torch
from flask import Flask, Response, request
from FlagEmbedding import FlagReranker
from transformers import AutoTokenizer
import importlib.metadata

# ---- Frozen canonical reranker quality config constants ------------------
MODEL_ID = "BAAI/bge-reranker-v2-m3"
INFERENCE_STACK = "FlagEmbedding::FlagReranker"
MAX_LENGTH = 512
TRUNCATION_POLICY = "LEFT_TRUNCATE_QUERY_RIGHT_TRUNCATE_DOCUMENT_OR_ERROR"
NORMALIZATION = False
SCORE_SEMANTICS = "raw cross-encoder relevance logit, higher = more relevant"
RANKING_DIRECTION = "DESCENDING"
TIE_BREAK_POLICY = "PRESERVE_ORIGINAL_HYBRID_CANDIDATE_ORDER"
PRECISION = "FP32"
INFERENCE_MODE = "model.eval() + torch.no_grad() / inference_mode"
INPUT_PAIR_SEMANTICS = "[query, document], no instruction, no prompt augmentation"

# ---- HTTP ----------------------------------------------------------------
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 18182

app = Flask(__name__)
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    stream=sys.stdout,
)
logger = logging.getLogger("local-reranker-gateway")

# Runtime state (populated by main())
reranker: FlagReranker | None = None
tokenizer: AutoTokenizer | None = None
runtime_info: dict[str, Any] = {}


# ---- Canonicalization helpers --------------------------------------------
def canonical_json(value: Any) -> str:
    """Deterministic JSON: sorted object keys, no whitespace, no timestamps.

    Non-ASCII characters are kept as UTF-8 literals to match the Java canonical
    serializer (EvalHashing) used for the retrieval config hash chain.
    """
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


# ---- Truncation policy ---------------------------------------------------
def count_special_tokens(query: str, document: str, tokenizer: AutoTokenizer) -> int:
    """Return number of special tokens added when encoding [query, document] as a pair."""
    with_special = tokenizer(query, document, add_special_tokens=True, max_length=None)
    without_special_query = tokenizer.encode(query, add_special_tokens=False)
    without_special_doc = tokenizer.encode(document, add_special_tokens=False)
    return len(with_special["input_ids"]) - len(without_special_query) - len(without_special_doc)


def _pair_length(query: str, document: str, tokenizer: AutoTokenizer) -> int:
    """Length of the full [query, document] pair including special tokens."""
    return len(tokenizer(query, document, add_special_tokens=True, max_length=None)["input_ids"])


def apply_truncation_policy(
    query: str, document: str, tokenizer: AutoTokenizer, max_length: int = MAX_LENGTH
) -> tuple[str, str]:
    """
    LEFT_TRUNCATE_QUERY_RIGHT_TRUNCATE_DOCUMENT_OR_ERROR.

    1. Tokenize query and document separately (no special tokens).
    2. If query + document + special tokens <= max_length, return as-is.
    3. Otherwise:
       - Truncate query from the left (keep tail tokens) so that the document
         can remain as complete as possible.
       - If the document is still too long even with a minimal query,
         truncate document from the right (keep head tokens).
       - If even a minimal (1-token) query + minimal (1-token) document does
         not fit, raise ValueError.

    The final decoded pair is verified with the tokenizer; if decode/re-encode
    expands the length, the budget is reduced iteratively until it fits.
    """
    query_tokens = tokenizer.encode(query, add_special_tokens=False)
    doc_tokens = tokenizer.encode(document, add_special_tokens=False)
    special_count = count_special_tokens(query, document, tokenizer)
    available = max_length - special_count

    if len(query_tokens) + len(doc_tokens) <= available:
        return query, document

    logger.debug(
        "Truncation required: query=%d doc=%d special=%d available=%d max_length=%d",
        len(query_tokens), len(doc_tokens), special_count, available, max_length,
    )

    # Determine initial token budgets following the policy.
    # Document has priority: keep full document if possible, truncate query from left.
    if len(doc_tokens) >= available - 1:
        doc_budget = max(1, available - 1)
        query_budget = 1 if query_tokens else 0
    else:
        doc_budget = len(doc_tokens)
        query_budget = max(1, available - doc_budget)
        query_budget = min(query_budget, len(query_tokens))

    # Iteratively reduce budgets if decode/re-encode causes length expansion.
    # This is fail-closed: we never return a pair that exceeds max_length.
    for _ in range(max_length):
        query_tokens_trunc = query_tokens[-query_budget:] if query_budget > 0 else []
        doc_tokens_trunc = doc_tokens[:doc_budget] if doc_budget > 0 else []

        query_trunc = tokenizer.decode(
            query_tokens_trunc, skip_special_tokens=True, clean_up_tokenization_spaces=False
        )
        doc_trunc = tokenizer.decode(
            doc_tokens_trunc, skip_special_tokens=True, clean_up_tokenization_spaces=False
        )

        if _pair_length(query_trunc, doc_trunc, tokenizer) <= max_length:
            return query_trunc, doc_trunc

        # Reduce query budget first, then document budget.
        if query_budget > 1:
            query_budget -= 1
        elif doc_budget > 1:
            doc_budget -= 1
        else:
            break

    raise ValueError(
        f"Truncation policy error: cannot fit query + document within max_length={max_length}"
    )


# ---- Rerank inference ----------------------------------------------------
def rerank(query: str, documents: list[str]) -> list[dict[str, Any]]:
    """Execute real cross-encoder inference and return DashScope-compatible results."""
    if reranker is None or tokenizer is None:
        raise RuntimeError("Reranker not initialized")

    # Build truncated [query, document] pairs with no instruction / prompt augmentation.
    pairs: list[list[str]] = []
    for doc in documents:
        q_trunc, d_trunc = apply_truncation_policy(query, doc, tokenizer, MAX_LENGTH)
        pairs.append([q_trunc, d_trunc])

    # FP32 / eval / no_grad evidence is captured in runtime_info during startup.
    with torch.no_grad(), torch.inference_mode():
        # normalize=False => raw logit; max_length honored via pre-truncation + guard.
        scores = reranker.compute_score(pairs, normalize=NORMALIZATION, max_length=MAX_LENGTH)

    # Build index-preserving results.
    results = [{"index": i, "relevance_score": float(score)} for i, score in enumerate(scores)]

    # Validate finite scores.
    for r in results:
        score = r["relevance_score"]
        if math.isnan(score) or math.isinf(score):
            raise RuntimeError(f"Non-finite score at index {r['index']}: {score}")

    # Descending ranking; stable sort preserves original hybrid candidate order on ties.
    results.sort(key=lambda x: x["relevance_score"], reverse=True)

    # Validate unique indices after sort.
    indices = [r["index"] for r in results]
    if len(set(indices)) != len(indices):
        raise RuntimeError("Duplicate rerank result indices")

    return results


# ---- HTTP handlers -------------------------------------------------------
@app.route("/health", methods=["GET"])
def health() -> Response:
    return Response(canonical_json({"status": "ok", "model_id": MODEL_ID}), mimetype="application/json")


@app.route("/info", methods=["GET"])
def info() -> Response:
    """Expose frozen canonical reranker quality config for audit."""
    return Response(canonical_json(runtime_info), mimetype="application/json")


@app.route("/", methods=["POST"], defaults={"path": ""})
@app.route("/<path:path>", methods=["POST"])
def rerank_endpoint(path: str = "") -> Response:
    """DashScope-compatible rerank endpoint."""
    data = request.get_json(silent=True)
    if data is None:
        return Response(canonical_json({"error": "Invalid JSON body"}), status=400, mimetype="application/json")

    if not isinstance(data.get("input"), dict):
        return Response(canonical_json({"error": "Missing input"}), status=400, mimetype="application/json")

    query = data["input"].get("query")
    documents = data["input"].get("documents")
    if not isinstance(query, str) or not isinstance(documents, list) or not documents:
        return Response(
            canonical_json({"error": "input.query must be a non-empty string and input.documents a non-empty list"}),
            status=400,
            mimetype="application/json",
        )

    # Strip any trailing None / empty documents to keep the contract clean.
    documents = [str(d) for d in documents if d is not None]
    if not documents:
        return Response(
            canonical_json({"error": "input.documents must contain at least one non-null document"}),
            status=400,
            mimetype="application/json",
        )

    try:
        results = rerank(query, documents)
    except ValueError as e:
        logger.warning("Truncation error: %s", e)
        return Response(canonical_json({"error": f"Truncation policy error: {e}"}), status=422, mimetype="application/json")
    except Exception as e:
        logger.exception("Rerank inference failed")
        return Response(canonical_json({"error": f"Rerank inference failed: {e}"}), status=503, mimetype="application/json")

    return Response(canonical_json({"results": results}), mimetype="application/json")


# ---- Startup / initialization --------------------------------------------
def load_runtime_info() -> dict[str, Any]:
    """Collect exact runtime identity for the canonical reranker quality config."""
    import platform

    torch_version = torch.__version__
    transformers_version = __import__("transformers").__version__
    flagembedding_version = importlib.metadata.version("FlagEmbedding")

    model_dtype = str(next(reranker.model.parameters()).dtype) if reranker else "unknown"
    model_device = str(next(reranker.model.parameters()).device) if reranker else "unknown"
    training_mode = "training" if (reranker and reranker.model.training) else "eval"

    return {
        "model_id": MODEL_ID,
        "inference_stack": INFERENCE_STACK,
        "max_length": MAX_LENGTH,
        "truncation_policy": TRUNCATION_POLICY,
        "normalization": NORMALIZATION,
        "score_semantics": SCORE_SEMANTICS,
        "ranking_direction": RANKING_DIRECTION,
        "tie_break_policy": TIE_BREAK_POLICY,
        "precision": PRECISION,
        "inference_mode": INFERENCE_MODE,
        "input_pair_semantics": INPUT_PAIR_SEMANTICS,
        "runtime": {
            "python_version": platform.python_version(),
            "torch_version": torch_version,
            "transformers_version": transformers_version,
            "flagembedding_version": flagembedding_version,
            "model_dtype": model_dtype,
            "model_device": model_device,
            "model_training_mode": training_mode,
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Local real reranker gateway")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--model", default=MODEL_ID)
    parser.add_argument("--max-length", type=int, default=MAX_LENGTH)
    args = parser.parse_args()

    global reranker, tokenizer, runtime_info

    logger.info("Loading model %s via %s ...", args.model, INFERENCE_STACK)
    # FlagReranker loads model + tokenizer. Use FP32 (default), no quantization.
    reranker = FlagReranker(args.model, use_fp16=False)
    tokenizer = AutoTokenizer.from_pretrained(args.model)

    # Ensure eval mode and no_grad/inference_mode are used at inference time.
    reranker.model.eval()

    runtime_info = load_runtime_info()
    logger.info("Model loaded: dtype=%s device=%s mode=%s", runtime_info["runtime"]["model_dtype"], runtime_info["runtime"]["model_device"], runtime_info["runtime"]["model_training_mode"])
    logger.info("Gateway listening on http://%s:%d", args.host, args.port)

    app.run(host=args.host, port=args.port, threaded=True)


if __name__ == "__main__":
    main()
