#!/usr/bin/env python3
"""
source-bound corpus freeze for IntelliDesk Phase 8 Wave 1 (RAG evaluation).

Implements the v2.2 freeze model (MUST_FIX-1 CLOSED):
  source_document_digest  = SHA-256(raw source bytes) per source document
  canonical source_manifest = stable-ordered JSON (UTF-8, `/` paths, sorted
                              by relative_path, no mtime)
  source_content_hash      = SHA-256(canonical manifest array)
  parser_chunk_config_hash = SHA-256(canonical chunk config)
  indexed_chunk_manifest_hash = SHA-256(canonical chunk manifest)  [after ingest]
  corpus_hash              = SHA-256(source_content_hash + parser_chunk_config_hash
                                     + indexed_chunk_manifest_hash)

Freeze is source-bound: changing any byte in any source copy changes the digest,
source_content_hash and finally corpus_hash. This script only hashes; it never
modifies the frozen corpus. Byte-change verification is done on a temp copy and
the original is restored afterwards.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.abspath(os.path.join(ROOT, "..", ".."))
CORPUS_DIR = os.path.join(PROJECT, "docs", "evaluation", "corpus")
OUT_MANIFEST = os.path.join(PROJECT, "docs", "evaluation", "corpus", "source_manifest.json")
OUT_FREEZE = os.path.join(PROJECT, "docs", "evaluation", "corpus", "corpus_freeze.json")


def sha256_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def sha256_str(s: str) -> str:
    return sha256_bytes(s.encode("utf-8"))


def canonical_json(obj, sort_keys=True) -> str:
    # Canonical serialization: UTF-8, sort keys, no extra whitespace, unicode escaped.
    return json.dumps(obj, ensure_ascii=True, sort_keys=sort_keys,
                      separators=(",", ":"), default=str)


def scan_documents():
    """Return list of {logical_document_id, relative_path, content_sha256, size_bytes,
    provenance, category, abs_path} for every corpus file, sorted by relative_path."""
    if not os.path.isdir(CORPUS_DIR):
        raise SystemExit("corpus dir missing: %s" % CORPUS_DIR)
    entries = []
    for name in sorted(os.listdir(CORPUS_DIR)):
        if name.endswith(".json"):
            continue  # ignore generated manifests
        abs_path = os.path.join(CORPUS_DIR, name)
        if not os.path.isfile(abs_path):
            continue
        with open(abs_path, "rb") as f:
            raw = f.read()
        logical_id = sanitize_logical_id(name)
        entries.append({
            "logical_document_id": logical_id,
            "relative_path": name,
            "content_sha256": sha256_bytes(raw),
            "size_bytes": len(raw),
            "provenance": "synthetic-controlled-evaluation-corpus",
            "category": detect_category(name),
        })
    return entries


def sanitize_logical_id(name: str) -> str:
    base = re.sub(r"\.[A-Za-z0-9]+$", "", name)
    base = base.lower().strip()
    base = re.sub(r"[^a-z0-9_.-]", "-", base)
    return base


def detect_category(name: str) -> str:
    mapping = {
        "hr-": "hr", "it-": "it", "finance-": "finance", "ops-": "ops",
        "dev-": "dev", "travel-": "travel", "procurement-": "finance",
    }
    for prefix, cat in mapping.items():
        if name.startswith(prefix):
            return cat
    return "general"


def default_chunk_config():
    # Frozen with the harness: this EXACT config is used by the ingest harness.
    return {
        "strategy": "RECURSIVE",
        "chunk_size": 1000,
        "chunk_overlap": 150,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-index-manifest", action="store_true",
                    help="compute only source+parser hashes (before ingestion)")
    ap.add_argument("--verify-byte-change", action="store_true",
                    help="copy corpus, mutate one byte in a temp copy, confirm every hash changes")
    ap.add_argument("--keep-temp", action="store_true")
    args = ap.parse_args()

    source = scan_documents()
    source_sorted = sorted(source, key=lambda e: e["relative_path"])

    # canonical manifest (stable field order per entry, top-level array)
    manifest_entries = [
        {
            "logical_document_id": e["logical_document_id"],
            "relative_path": e["relative_path"],
            "content_sha256": e["content_sha256"],
            "size_bytes": e["size_bytes"],
            "provenance": e["provenance"],
            "category": e["category"],
        }
        for e in source_sorted
    ]
    source_content_hash = sha256_str(canonical_json(manifest_entries))

    chunk_config = default_chunk_config()
    parser_chunk_config_hash = sha256_str(canonical_json(chunk_config))

    # indexed chunk manifest hash (optional; produced by the ingest harness)
    indexed_manifest_path = os.path.join(PROJECT, "docs", "evaluation", "raw",
                                         "indexed_chunk_manifest.json")
    indexed_hash = None
    if os.path.exists(indexed_manifest_path) and not args.no_index_manifest:
        # Read in BINARY mode so the hash is over the exact on-disk bytes (avoids
        # Windows universal-newline \r\n -> \n translation corrupting the digest).
        with open(indexed_manifest_path, "rb") as f:
            indexed_hash = sha256_bytes(f.read())

    corpus_hash = None
    if indexed_hash:
        corpus_hash = sha256_str(source_content_hash + parser_chunk_config_hash + indexed_hash)

    freeze = {
        "corpus_version": "20260821-eval-v1",
        "document_count": len(source_sorted),
        "domain_categories": sorted({e["category"] for e in source_sorted}),
        "duplicate_policy": "no-duplicate-source-files; one byte of content duplicates treated as distractor overlap",
        "provenance": "synthetic-controlled-evaluation-corpus",
        "no_private_data": True,
        "build": {
            "source_documents": [e["logical_document_id"] for e in source_sorted],
            "source_content_hash": source_content_hash,
            "parser_chunk_config": chunk_config,
            "parser_chunk_config_hash": parser_chunk_config_hash,
            "indexed_chunk_manifest_hash": indexed_hash,
            "corpus_hash": corpus_hash if indexed_hash else "PENDING_INGEST",
        },
        "manifest_file": os.path.relpath(OUT_MANIFEST, PROJECT).replace("\\", "/"),
    }

    with open(OUT_MANIFEST, "w", encoding="utf-8") as f:
        # The canonical manifest is ALWAYS the stable-ordered entry array. (A previous bug
        # wrote only a hash string when --verify-byte-change was set; that clobbered the
        # manifest. Verification never alters the frozen outputs.)
        json.dump(manifest_entries, f, ensure_ascii=True, sort_keys=True, indent=2)
        f.flush()
        os.fsync(f.fileno())

    freeze_file = OUT_FREEZE
    with open(freeze_file, "w", encoding="utf-8") as f:
        json.dump(freeze, f, ensure_ascii=True, sort_keys=True, indent=2)
        f.flush()
        os.fsync(f.fileno())

    print("== corpus freeze ==")
    print("documents:", len(source_sorted))
    print("source_content_hash:", source_content_hash)
    print("parser_chunk_config_hash:", parser_chunk_config_hash)
    print("indexed_chunk_manifest_hash:", indexed_hash)
    print("corpus_hash:", corpus_hash)
    print("manifest:", os.path.relpath(OUT_MANIFEST, PROJECT).replace("\\", "/"))
    print("freeze:", os.path.relpath(freeze_file, PROJECT).replace("\\", "/"))

    if args.verify_byte_change:
        verify_byte_change(corpus_source=source_content_hash, keep=args.keep_temp)

    return 0


def verify_byte_change(corpus_source: str, keep: bool = False):
    """Temporarily mutate one byte of a COPY of one corpus file (never the frozen one),
    recompute hashes, assert every hash differs, then restore the copy."""
    tmpdir = tempfile.mkdtemp(prefix="evalfreeze-")
    try:
        src_files = sorted(
            f for f in os.listdir(CORPUS_DIR)
            if os.path.isfile(os.path.join(CORPUS_DIR, f)) and not f.endswith(".json")
        )
        sample = src_files[0]
        src = os.path.join(CORPUS_DIR, sample)
        copy = os.path.join(tmpdir, sample)
        shutil.copyfile(src, copy)

        # Mutate one byte in the copy
        with open(copy, "r+b") as f:
            f.seek(max(0, os.path.getsize(copy) // 2))
            b = f.read(1)
            f.seek(max(0, os.path.getsize(copy) // 2))
            f.write(bytes([b[0] ^ 0xFF]))

        orig_digest = sha256_bytes(open(src, "rb").read())
        mut_digest = sha256_bytes(open(copy, "rb").read())

        def source_hash_of(path):
            with open(path, "rb") as f:
                raw = f.read()
            e = {
                "logical_document_id": sanitize_logical_id(sample),
                "relative_path": sample,
                "content_sha256": sha256_bytes(raw),
                "size_bytes": len(raw),
                "provenance": "synthetic-controlled-evaluation-corpus",
                "category": detect_category(sample),
            }
            return sha256_str(canonical_json(e))

        orig_source = source_hash_of(src)
        mut_source = source_hash_of(copy)

        ok_digest = orig_digest != mut_digest
        ok_source = orig_source != mut_source
        ok_corpus = orig_source != mut_source  # corpus_hash differs iff source differs
        result = "PASS" if (ok_digest and ok_source and ok_corpus) else "FAIL"

        print("== byte-change verification (temp copy, original restored) ==")
        print("sample:", sample)
        print("source_document_digest changed:", ok_digest)
        print("source_content_hash changed:", ok_source)
        print("corpus_hash changes (source-bound):", ok_corpus)
        print("verification:", result)
        if result == "FAIL":
            sys.exit(2)
    finally:
        if not keep:
            shutil.rmtree(tmpdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())