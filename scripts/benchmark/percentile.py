#!/usr/bin/env python3
"""Offline percentile calculator for Phase 8 Wave 2 benchmark raw.

Uses nearest-rank definition. k6 aggregate summary is only a cross-check;
this calculator operates on persisted per-sample raw latency_ms values.

Supported percentiles: p50, p90, p95, p99.
"""

import argparse
import json
import math
import sys
from pathlib import Path


def compute_percentiles(latencies: list[float]) -> dict[str, float]:
    """Compute p50/p90/p95/p99 using nearest-rank."""
    if not latencies:
        raise ValueError("latencies must not be empty")

    sorted_latencies = sorted(latencies)
    n = len(sorted_latencies)
    result: dict[str, float] = {}
    for rank in (50, 90, 95, 99):
        ordinal = math.ceil(rank / 100.0 * n)
        index = min(max(ordinal, 1), n) - 1
        result[f"p{rank}"] = sorted_latencies[index]
    return result


def load_latencies(raw_jsonl_path: Path, phase: str = "sustain") -> list[float]:
    """Load latency_ms values from a benchmark raw JSONL file.

    Only samples explicitly carrying the requested phase are included. Missing,
    null, or unknown phase provenance is excluded rather than being treated as
    sustain, so authoritative latency statistics fail closed.
    """
    latencies: list[float] = []
    with open(raw_jsonl_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            record = json.loads(line)
            record_phase = record.get("phase")
            if record_phase != phase:
                continue
            value = record.get("latency_ms")
            if isinstance(value, (int, float)):
                latencies.append(float(value))
    return latencies


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Offline percentile calculator")
    parser.add_argument("raw_jsonl", help="Path to benchmark raw JSONL file")
    parser.add_argument("--output", help="Output JSON file")
    args = parser.parse_args(argv)

    raw_path = Path(args.raw_jsonl)
    latencies = load_latencies(raw_path)
    percentiles = compute_percentiles(latencies)

    result = {
        "input": str(raw_path),
        "sample_count": len(latencies),
        "percentiles": percentiles,
        "method": "nearest-rank",
    }

    print(json.dumps(result, ensure_ascii=False, indent=2))

    if args.output:
        Path(args.output).write_text(
            json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
