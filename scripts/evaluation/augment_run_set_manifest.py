#!/usr/bin/env python3
"""Augment an existing run-set manifest with canonical evidence entries and linkage hashes.

This is a one-time tooling step for the already-finalized COMPLETE run-set
implementation-compensation-001. It does not touch raw result files; it only
collects metadata (size_bytes/sha256) from them and reads corpus/dataset hashes
from the frozen freeze artifacts.
"""

import hashlib
import json
import sys
from pathlib import Path


def sha256_file(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    run_set_dir = root / "docs" / "evaluation" / "raw" / "real-quality" / "2ae529f6aa1ba508" / "implementation-compensation-001"
    manifest_path = run_set_dir / "run_set_manifest.json"

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    # Read corpus_hash from corpus freeze artifact.
    corpus_freeze = json.loads((root / "docs" / "evaluation" / "corpus" / "corpus_freeze.json").read_text(encoding="utf-8"))
    corpus_hash = corpus_freeze["build"]["corpus_hash"]

    # Read dataset_hash from dataset freeze artifact.
    dataset_freeze = json.loads((root / "docs" / "evaluation" / "dataset" / "dataset_freeze.json").read_text(encoding="utf-8"))
    dataset_hash = dataset_freeze["dataset_hash"]

    # Collect evidence entries for every eval-*.json in the run-set directory.
    entries = []
    for p in sorted(run_set_dir.glob("eval-*.json")):
        rel = "/".join(p.relative_to(root).parts)
        entries.append({
            "relative_path": rel,
            "size_bytes": p.stat().st_size,
            "sha256": sha256_file(p),
        })

    completed_modes = sorted({p.stem.split("-")[1] for p in run_set_dir.glob("eval-*.json")})
    completed_runs_per_mode = max({int(p.stem.split("-")[2]) for p in run_set_dir.glob("eval-*.json")})

    # Rebuild with stable, logical field order.
    ordered = {
        "schema_version": manifest.get("schema_version", "1.0"),
        "evidence_class": manifest.get("evidence_class"),
        "config_hash": manifest.get("config_hash"),
        "config_id": manifest.get("config_id"),
        "corpus_hash": corpus_hash,
        "dataset_hash": dataset_hash,
        "run_set_id": manifest.get("run_set_id"),
        "actor": manifest.get("actor"),
        "purpose": manifest.get("purpose"),
        "expected_modes": manifest.get("expected_modes"),
        "expected_runs_per_mode": manifest.get("expected_runs_per_mode"),
        "status": manifest.get("status"),
        "created_at": manifest.get("created_at"),
        "completed_at": manifest.get("completed_at"),
        "completed_modes": completed_modes,
        "completed_runs_per_mode": completed_runs_per_mode,
        "evidence_entries": entries,
    }
    if "failure_reason" in manifest:
        ordered["failure_reason"] = manifest["failure_reason"]

    # Canonical stable serialization (no manifest_hash for run-set manifest; stable field order kept by insertion).
    manifest_path.write_text(json.dumps(ordered, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"Augmented {manifest_path}")
    print(f"  corpus_hash: {corpus_hash}")
    print(f"  dataset_hash: {dataset_hash}")
    print(f"  completed_modes: {completed_modes}")
    print(f"  completed_runs_per_mode: {completed_runs_per_mode}")
    print(f"  evidence_entries: {len(entries)}")
    for e in entries:
        print(f"    {e['relative_path']} size={e['size_bytes']} sha256={e['sha256']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
