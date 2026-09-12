#!/usr/bin/env python3
"""Verify HTTP Concurrency custom/native duration semantic equivalence.

This is a diagnostic/test helper only. It compares the complete multiset of
HTTP Concurrency `bench_req_duration` values with k6 `http_req_duration`
values from one k6 JSON stream. It never treats the stream as Formal evidence.
"""

import argparse
import json
import math
import sys
from pathlib import Path


def _metric_values(path: Path, metric: str) -> list[float]:
    values: list[float] = []
    with path.open("r", encoding="utf-8") as stream:
        for line in stream:
            if not line.strip():
                continue
            record = json.loads(line)
            if record.get("type") != "Point" or record.get("metric") != metric:
                continue
            value = record.get("data", {}).get("value")
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise ValueError(f"{metric} contains a non-numeric Point value")
            numeric = float(value)
            if not math.isfinite(numeric) or numeric < 0:
                raise ValueError(f"{metric} contains an invalid duration")
            values.append(numeric)
    return values


def verify(path: Path) -> dict:
    custom = _metric_values(path, "bench_req_duration")
    native = _metric_values(path, "http_req_duration")
    custom_sorted = sorted(custom)
    native_sorted = sorted(native)
    exact_multiset_match = custom_sorted == native_sorted
    fractional_custom_count = sum(not value.is_integer() for value in custom)
    result = {
        "schema_version": "1.0",
        "classification": "NOT_BENCHMARK_EVIDENCE",
        "input": str(path),
        "canonical_metric": "bench_req_duration",
        "native_reference_metric": "http_req_duration",
        "request_boundary": "sending_plus_waiting_plus_receiving_excludes_blocked_dns_connect_tls",
        "custom_sample_count": len(custom),
        "native_sample_count": len(native),
        "fractional_custom_sample_count": fractional_custom_count,
        "exact_value_multiset_match": exact_multiset_match,
        "status": (
            "PASS"
            if custom and len(custom) == len(native) and fractional_custom_count > 0
            and exact_multiset_match
            else "FAIL"
        ),
    }
    return result


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("k6_json")
    parser.add_argument("--output")
    args = parser.parse_args(argv)
    result = verify(Path(args.k6_json))
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        output = Path(args.output)
        if output.exists():
            raise FileExistsError(f"FAIL_IF_EXISTS: {output}")
        output.write_text(rendered + "\n", encoding="utf-8")
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
