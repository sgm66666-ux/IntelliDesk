#!/usr/bin/env python3
"""Verify current final real raw files still match the pre-change baseline manifest."""

import hashlib
import json
import sys
from pathlib import Path


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    manifest_path = root / "docs" / "evaluation" / "raw" / "final_real_raw_manifest.json"
    if not manifest_path.exists():
        print(f"MISSING baseline manifest: {manifest_path}", file=sys.stderr)
        return 2

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    expected_entries = {e["relative_path"]: e for e in manifest["entries"]}

    ok = True
    for rel, expected in sorted(expected_entries.items()):
        p = root / rel.replace("/", "\\")
        if not p.exists():
            print(f"MISSING: {rel}")
            ok = False
            continue
        data = p.read_bytes()
        actual_size = len(data)
        actual_sha256 = hashlib.sha256(data).hexdigest()
        if actual_size != expected["size_bytes"] or actual_sha256 != expected["sha256"]:
            print(f"MISMATCH: {rel}")
            print(f"  expected size={expected['size_bytes']} sha256={expected['sha256']}")
            print(f"  actual   size={actual_size} sha256={actual_sha256}")
            ok = False
        else:
            print(f"OK: {rel} size={actual_size} sha256={actual_sha256}")

    if ok:
        print("ALL 8 FINAL REAL RAW FILES ARE BYTE-IDENTICAL TO BASELINE")
        return 0
    else:
        print("BASELINE VERIFICATION FAILED", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
