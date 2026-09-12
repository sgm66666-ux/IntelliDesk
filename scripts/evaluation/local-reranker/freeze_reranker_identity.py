#!/usr/bin/env python3
"""
Phase 8 Wave 1 — Local Real Reranker Identity Freeze.

Produces the canonical reranker quality config artifact and model/tokenizer/inference-stack
identity evidence required by the approved scope amendment v1.1.

Outputs (all written to scripts/evaluation/local-reranker/identity/):
  - requirements.lock              exact Python dependency versions (canonical subset)
  - model_artifact_manifest.json   stable local model file manifest (relative path, size, sha256)
  - reranker_quality_config.json   canonical frozen reranker quality config
  - reranker_quality_config_hash   SHA-256 of canonical config (one line)

The model artifact manifest uses stable path normalization relative to the Hugging Face
model snapshot root, sorted deterministically, with no absolute paths / mtimes / PIDs.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
import platform
import subprocess
import sys
from pathlib import Path
from typing import Any

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

# Relative to this script.
SCRIPT_DIR = Path(__file__).resolve().parent
OUT_DIR = SCRIPT_DIR / "identity"

# Packages that can affect inference semantics (tokenization, padding, dtype, scoring).
RESULT_AFFECTING_PACKAGES = {
    "FlagEmbedding",
    "torch",
    "transformers",
    "tokenizers",
    "sentence-transformers",
    "peft",
    "accelerate",
    "safetensors",
    "huggingface_hub",
    "numpy",
    "regex",
}


def canonical_json(value: Any) -> str:
    """Deterministic JSON: sorted object keys, compact separators, UTF-8 literals, no timestamps.

    Non-ASCII characters are kept as UTF-8 literals to match the Java canonical
    serializer (EvalHashing) used for the retrieval config hash chain.
    """
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_str(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def pip_freeze_subset() -> list[str]:
    """Return exact versions of result-affecting packages from the current environment."""
    result = subprocess.run(
        [sys.executable, "-m", "pip", "freeze"],
        capture_output=True,
        text=True,
        check=True,
    )
    lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    subset = []
    for line in lines:
        # Match "name==version" or "name @ url".
        name = line.split("==")[0].split(" @")[0].strip()
        if name in RESULT_AFFECTING_PACKAGES:
            subset.append(line)
    subset.sort(key=lambda s: s.lower())
    return subset


def find_model_snapshot_root(model_id: str) -> tuple[Path, str]:
    """Locate the HF hub snapshot directory and return (snapshot_root, revision_sha)."""
    safe_name = model_id.replace("/", "--")
    cache_dir = Path.home() / ".cache" / "huggingface" / "hub" / f"models--{safe_name}"
    if not cache_dir.exists():
        raise FileNotFoundError(f"HF cache not found for {model_id}: {cache_dir}")
    snapshots = cache_dir / "snapshots"
    if not snapshots.exists():
        raise FileNotFoundError(f"No snapshots directory under {cache_dir}")
    entries = [d for d in snapshots.iterdir() if d.is_dir()]
    if len(entries) != 1:
        raise RuntimeError(f"Expected exactly one snapshot, got {len(entries)}: {entries}")
    revision = entries[0].name
    return entries[0], revision


def build_model_artifact_manifest(snapshot_root: Path) -> dict[str, Any]:
    """Build a stable manifest of every file under the snapshot root."""
    files: list[dict[str, Any]] = []
    for path in sorted(snapshot_root.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(snapshot_root).as_posix()
        files.append({
            "relative_path": rel,
            "size_bytes": path.stat().st_size,
            "sha256": sha256_file(path),
        })
    return {
        "model_id": MODEL_ID,
        "snapshot_root_relative": f"models--{MODEL_ID.replace('/', '--')}/snapshots/<revision>",
        "files": files,
    }


def model_artifact_identity(manifest: dict[str, Any]) -> str:
    """SHA-256 of the canonical model artifact manifest (no absolute paths, stable sort)."""
    return sha256_str(canonical_json(manifest))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out-dir", default=str(OUT_DIR))
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # 1. Dependency lock
    lock_lines = pip_freeze_subset()
    # Ensure FlagEmbedding version is always present even if pip freeze omits it.
    flagembedding_version = importlib.metadata.version("FlagEmbedding")
    if not any(line.lower().startswith("flagembedding") for line in lock_lines):
        lock_lines.append(f"FlagEmbedding=={flagembedding_version}")
    lock_lines.sort(key=lambda s: s.lower())
    lock_path = out_dir / "requirements.lock"
    lock_path.write_text("\n".join(lock_lines) + "\n", encoding="utf-8")

    # 2. Model / tokenizer artifact manifest
    snapshot_root, revision = find_model_snapshot_root(MODEL_ID)
    manifest = build_model_artifact_manifest(snapshot_root)
    manifest_path = out_dir / "model_artifact_manifest.json"
    manifest_path.write_text(canonical_json(manifest), encoding="utf-8")

    model_artifact_identity_hash = model_artifact_identity(manifest)
    tokenizer_artifact_identity = f"same as model_artifact_identity ({model_artifact_identity_hash})"

    # 3. Inference stack version identity = canonical hash of dependency lock
    inference_stack_version_identity = sha256_str("\n".join(lock_lines))

    # 4. Canonical reranker quality config
    reranker_quality_config = {
        "provider_mode": "LOCAL_REAL_GATEWAY",
        "model_id": MODEL_ID,
        "model_revision_identity": revision,
        "model_artifact_identity": model_artifact_identity_hash,
        "tokenizer_artifact_identity": tokenizer_artifact_identity,
        "inference_stack": INFERENCE_STACK,
        "inference_stack_version_identity": inference_stack_version_identity,
        "input_pair_semantics": INPUT_PAIR_SEMANTICS,
        "max_length": MAX_LENGTH,
        "truncation_policy": TRUNCATION_POLICY,
        "normalization": NORMALIZATION,
        "score_semantics": SCORE_SEMANTICS,
        "ranking_direction": RANKING_DIRECTION,
        "tie_break_policy": TIE_BREAK_POLICY,
        "precision": PRECISION,
        "inference_mode": INFERENCE_MODE,
    }

    config_path = out_dir / "reranker_quality_config.json"
    config_path.write_text(canonical_json(reranker_quality_config), encoding="utf-8")
    config_hash = sha256_str(canonical_json(reranker_quality_config))
    (out_dir / "reranker_quality_config_hash").write_text(config_hash + "\n", encoding="utf-8")

    print("== reranker identity freeze ==")
    print(f"model_id: {MODEL_ID}")
    print(f"model_revision_identity: {revision}")
    print(f"model_artifact_identity: {model_artifact_identity_hash}")
    print(f"inference_stack_version_identity: {inference_stack_version_identity}")
    print(f"reranker_quality_config_hash: {config_hash}")
    print(f"outputs: {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
