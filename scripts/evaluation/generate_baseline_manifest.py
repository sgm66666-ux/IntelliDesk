#!/usr/bin/env python3
"""Generate canonical pre-change baseline manifest for final real raw files."""

import hashlib
import json
import sys
from pathlib import Path


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    raw_dir = root / "docs" / "evaluation" / "raw"
    files = [
        "eval-VECTOR-1.json",
        "eval-VECTOR-2.json",
        "eval-KEYWORD-1.json",
        "eval-KEYWORD-2.json",
        "eval-HYBRID-1.json",
        "eval-HYBRID-2.json",
        "eval-HYBRID_RERANK-1.json",
        "eval-HYBRID_RERANK-2.json",
    ]

    entries = []
    for name in files:
        p = raw_dir / name
        if not p.exists():
            print(f"MISSING: {p}", file=sys.stderr)
            return 1
        data = p.read_bytes()
        entries.append({
            "relative_path": f"docs/evaluation/raw/{name}",
            "size_bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        })

    entries.sort(key=lambda x: x["relative_path"])

    manifest = {
        "schema_version": "1.0",
        "evidence_class": "protected-final-real-legacy",
        "description": "Pre-change baseline manifest for the 8 implementation-side final real raw files before output-integrity namespace refactoring.",
        "retrieval_config_hash": "2ae529f6aa1ba50845890374762191cc8a41543aec3c1eca37f49a0e7cc847a7",
        "config_id": "2ae529f6aa1ba508",
        "entries": entries,
    }

    # Canonical manifest excludes the manifest_hash field itself.
    canonical = json.dumps(manifest, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    manifest_hash = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    manifest["final_real_raw_manifest_hash"] = manifest_hash

    out = raw_dir / "final_real_raw_manifest.json"
    out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    print(f"Wrote {out}")
    print(f"Manifest hash: {manifest_hash}")
    for e in entries:
        print(f"  {e['relative_path']} size={e['size_bytes']} sha256={e['sha256']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
