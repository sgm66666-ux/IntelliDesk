"""Audit every Git-visible Public file without printing suspected secret values.

This complements the existing benchmark scanner, whose default roots intentionally
cover historical evidence rather than the new Showcase/source tree.
"""

from __future__ import annotations

import re
import hashlib
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "benchmark"))
from secret_scan import scan_file  # noqa: E402

TEXT_SUFFIXES = {
    ".java", ".vue", ".ts", ".tsx", ".js", ".mjs", ".cjs", ".py",
    ".md", ".txt", ".json", ".jsonl", ".tsv", ".csv", ".xml",
    ".yml", ".yaml", ".toml", ".properties", ".sh", ".ps1",
    ".css", ".html", ".sql", ".gitignore", ".example",
}
PRIVATE_KEY = re.compile(r"-----BEGIN (?:OPENSSH |RSA |EC |DSA )?PRIVATE KEY-----")
ABSOLUTE_LOCAL = re.compile(r"[DC]:\\(?:Projects|Users)\\", re.IGNORECASE)
SECRET_ASSIGNMENT = re.compile(
    r"(?i)\b(?:authorization|cookie|api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|credential)\b"
    r"\s*[:=]\s*['\"]([A-Za-z0-9_+/=.-]{24,})"
)

# Existing source/test/probe files reviewed on 2026-09-25: the generic benchmark
# sentinel matches literal test credentials, a synthetic preflight login and
# warning text such as "Bearer credentials presented". A byte change invalidates
# the exception, so newly introduced values cannot silently inherit it.
REVIEWED_FIXTURE_SHA256 = {
    "backend/src/main/java/com/intellidesk/api_key/ApiKeyAuthenticationFilter.java": "d3c5176a2a349ee4af97ec6772e095cd35ee86648fdaaeb9c04afddeb49bc29f",
    "backend/src/test/java/com/intellidesk/api_key/ApiKeyAuthenticationFilterTest.java": "79537b185f39a71da9dc168f6c5d808ad459007820a1f63bd03c80ec7195ca65",
    "backend/src/test/java/com/intellidesk/benchmark/BenchmarkHarnessToolingTest.java": "f58c1645d911cd72b1aca95c5a4ed410d077777293f68e359f80ef0e5ebed5fc",
    "deploy/e2e-phase7-wave3-seed.ps1": "de64c7df912f03543e664749e8b57fd43b09a573d225c49d2dab93cbb5c00c5d",
    "deploy/ratelimit_probe.sh": "3f88d863396c5dd7cb1f9725e5377b61cee58b7bb00b5b6462f8111e14a2b3ab",
    "deploy/ratelimit_spoof.sh": "72e9c557f2308b8b73dee457c50bf722d348527689b499ce16330ed7f08dbe8c",
    "scripts/benchmark/environment_identity.py": "0df745aaa15bcca06c2d41b6735bde934128641f88a9fd19ac9dfc9c6bfcd2b7",
    "scripts/benchmark/target_preflight.py": "51e712bb08af50c765f4f6aabdf051471029d80a4041562ff299bb8934f714d3",
    "scripts/benchmark/test_benchmark_tooling.py": "f7a93917a5f5e711ed6397e6d7054fd51675647927b41e24a48054d89981edc1",
    "scripts/benchmark/test_run_set_acceptance.py": "245acfea2a088956acc6f88d14acbfc637aa8656992c2573e8ad5e9d8ad54efb",
    "scripts/benchmark/test_secret_scan.py": "9d122f15386f81fbed1d5afc5a827060b2153a6611229bddd141cd2223d6e9e1",
}


def main() -> int:
    names = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT
    ).decode("utf-8").split("\0")
    findings: list[tuple[str, str]] = []
    scanned = 0
    reviewed = 0
    for name in filter(None, names):
        path = ROOT / name
        if not path.is_file():
            continue
        parts = set(Path(name).parts)
        if path.name == ".env" or (path.name.startswith(".env.") and path.name != ".env.example"):
            findings.append((name, "environment file"))
            continue
        if parts & {"node_modules", "dist", "target", "local-only", "__pycache__", "logs"}:
            findings.append((name, "non-public artifact"))
            continue
        if path.is_symlink():
            findings.append((name, "symlink requires manual inspection"))
            continue
        if path.suffix.lower() not in TEXT_SUFFIXES and path.name not in {"Dockerfile", ".gitignore"}:
            continue
        scanned += 1
        hits, sampled = scan_file(path)
        if sampled:
            findings.append((name, "large text file only sampled"))
        if hits:
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if REVIEWED_FIXTURE_SHA256.get(name.replace("\\", "/")) == digest:
                reviewed += 1
            else:
                findings.append((name, "benchmark scanner secret pattern"))
        for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
            if PRIVATE_KEY.search(line):
                findings.append((name, "private key marker"))
                break
            if ABSOLUTE_LOCAL.search(line):
                findings.append((name, "absolute local machine path"))
                break
            if SECRET_ASSIGNMENT.search(line) and not (name.endswith(".example") or "test" in name.lower()):
                findings.append((name, "credential-like assignment"))
                break
    if findings:
        for name, reason in sorted(set(findings)):
            print(f"REVIEW_REQUIRED {name}: {reason} [value redacted]")
        print(f"PUBLIC_CANDIDATE_SECRET_SCAN FAIL scanned_text_files={scanned} findings={len(set(findings))}")
        return 1
    print(f"PUBLIC_CANDIDATE_SECRET_SCAN PASS scanned_text_files={scanned} candidates={len(names)-1} reviewed_fixture_files={reviewed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
