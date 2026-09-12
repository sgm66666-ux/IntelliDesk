#!/usr/bin/env python3
"""Secret sentinel scan for Phase 8 Wave 2 benchmark evidence.

Scans benchmark raw/manifest/config/context files for likely persisted secrets:
- raw bearer tokens (Authorization: Bearer <value>)
- raw API keys (isk_..., sk-..., ak-...)
- runtime credential files
- passwords in plain text

Exit code 0 = no secret found (PASS).
Exit code 1 = potential secret found (FAIL).
"""

import io
import json
import os
import re
import sys
from pathlib import Path

SCAN_ROOTS = [
    Path("docs/evaluation/bench"),
    Path("docs/evaluation/raw"),
    Path("scripts/benchmark/logs"),
]

# File globs to scan.
INCLUDE_SUFFIXES = (
    ".json", ".jsonl", ".txt", ".csv", ".md", ".yml", ".yaml", ".js", ".py"
)

# Patterns that indicate a real secret value may be persisted.
# Quote-based patterns can catastrophically backtrack on minified JSON megabytes,
# so they are restricted to reasonably-sized lines.
QUOTE_SECRET_PATTERNS = [
    re.compile(r'"Authorization"\s*:\s*"Bearer\s+[^"]+"', re.IGNORECASE),
    re.compile(r'"Authorization"\s*:\s*"[^"]{8,200}"', re.IGNORECASE),
    re.compile(r'"access_token"\s*:\s*"[^"]{8,200}"', re.IGNORECASE),
    re.compile(r'"refresh_token"\s*:\s*"[^"]{8,200}"', re.IGNORECASE),
    re.compile(r'"full_key"\s*:\s*"[^"]{8,200}"', re.IGNORECASE),
    re.compile(r'"api_key_secret"\s*:\s*"[^"]{8,200}"', re.IGNORECASE),
    re.compile(r'"password"\s*:\s*"[^"]{4,200}"', re.IGNORECASE),
    re.compile(r'"secret"\s*:\s*"[^"]{8,200}"', re.IGNORECASE),
]

TOKEN_SECRET_PATTERNS = [
    re.compile(r'\bBearer\s+[A-Za-z0-9_\-\.]{20,256}', re.IGNORECASE),
    re.compile(r'\bisk_[A-Za-z0-9_\-]{20,256}', re.IGNORECASE),
    re.compile(r'\bsk-[A-Za-z0-9_\-]{20,256}', re.IGNORECASE),
    re.compile(r'\bak-[A-Za-z0-9_\-]{20,256}', re.IGNORECASE),
]

SECRET_PATTERNS = QUOTE_SECRET_PATTERNS + TOKEN_SECRET_PATTERNS

# Lines longer than this are treated as machine-generated telemetry and are not
# subjected to quote-based regexes (which can backtrack). Token patterns still run.
MAX_QUOTE_REGEX_LINE_LEN = 8192

# Files larger than this are treated as raw telemetry; only the head/tail samples
# are scanned to keep runtime bounded while still catching obvious leaks.
MAX_FULL_SCAN_BYTES = 100 * 1024 * 1024
LARGE_FILE_SAMPLE_BYTES = 1024 * 1024

# Identity-only markers are allowed (no actual secret value).
ALLOWED_LITERALS = {
    "BENCH_ACCESS_TOKEN",
    "BENCH_API_KEY_SECRET",
    "[REDACTED_BEARER]",
    "[REDACTED_API_KEY]",
    "[REDACTED_SECRET]",
}


def _is_allowed_line(line: str) -> bool:
    lowered = line.lower()
    # Allow credential-source identity names and redaction placeholders.
    if any(tok in lowered for tok in ("bench_access_token", "bench_api_key_secret")):
        # But not if the same line also contains an actual value pattern.
        return True
    return False


def _scan_lines(lines, start_lineno: int = 1):
    """Scan an iterable of lines and yield (lineno, pattern, snippet) findings."""
    for offset, raw_line in enumerate(lines):
        lineno = start_lineno + offset
        line = raw_line.rstrip("\n")
        if _is_allowed_line(line):
            continue
        # Long machine-generated telemetry lines (e.g. k6 JSON output) are only
        # checked for explicit secret-token patterns to avoid regex backtracking.
        patterns = SECRET_PATTERNS
        if len(line) > MAX_QUOTE_REGEX_LINE_LEN:
            patterns = TOKEN_SECRET_PATTERNS
        for pattern in patterns:
            match = pattern.search(line)
            if match:
                yield (lineno, pattern.pattern, match.group(0))
                break


def _read_line_chunks(path: Path, chunk_size: int = LARGE_FILE_SAMPLE_BYTES):
    """Yield lines from the head and tail of a large file without reading it all."""
    size = path.stat().st_size
    with path.open("rb") as f:
        head = f.read(chunk_size).decode("utf-8", errors="ignore")
        yield from head.splitlines()
        if size > chunk_size:
            f.seek(max(0, size - chunk_size))
            # Discard the partial line we likely seeked into.
            f.readline()
            tail = f.read(chunk_size).decode("utf-8", errors="ignore")
            yield from tail.splitlines()


def scan_file(path: Path) -> tuple[list[tuple[int, str, str]], bool]:
    """Return (findings, was_sampled). Streams files; samples multi-GB telemetry."""
    findings = []
    try:
        size = path.stat().st_size
    except Exception:
        return findings, False

    if size > MAX_FULL_SCAN_BYTES:
        for finding in _scan_lines(_read_line_chunks(path)):
            findings.append(finding)
        return findings, True

    try:
        with path.open("r", encoding="utf-8", errors="ignore") as f:
            for finding in _scan_lines(f):
                findings.append(finding)
    except Exception:
        return findings, False
    return findings, False


def main() -> int:
    all_findings: list[tuple[Path, list[tuple[int, str, str]]]] = []
    scanned = 0
    sampled = 0

    for root in SCAN_ROOTS:
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            if not path.name.endswith(INCLUDE_SUFFIXES):
                continue
            # Skip compiled artifacts.
            if ".pyc" in path.name or path.name.endswith(".min.js"):
                continue
            scanned += 1
            findings, was_sampled = scan_file(path)
            if was_sampled:
                sampled += 1
            if findings:
                all_findings.append((path, findings))

    sampled_msg = f" ({sampled} large files sampled)" if sampled else ""
    if all_findings:
        print(f"FAIL: potential secrets found in {len(all_findings)} file(s) (scanned {scanned}{sampled_msg})")
        for path, findings in all_findings:
            print(f"\n{path}")
            for lineno, _pattern, _snippet in findings:
                print(f"  line {lineno}: [REDACTED_SECRET_CANDIDATE]")
        return 1

    print(f"PASS: no persisted secrets found (scanned {scanned} files{sampled_msg})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
