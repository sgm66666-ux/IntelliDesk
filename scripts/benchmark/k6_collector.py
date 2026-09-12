#!/usr/bin/env python3
"""k6 JSON output collector for Phase 8 Wave 2 benchmark harness.

Parses k6 --out json=... output and writes per-sample raw records in
newline-delimited JSON format under the run-set directory.

Only bench_req_duration Point samples are converted; other metrics are skipped
but retained as execution_metadata in the converted samples.
"""

import json
import sys
from copy import deepcopy
from datetime import datetime
from pathlib import Path


SECRET_TAG_KEYS = {"authorization", "x-api-key", "cookie", "api-key", "apikey", "token",
                   "access_token", "access-token", "secret", "password"}


def _parse_iso_ms(value: str | None) -> float | None:
    if not value:
        return None
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return dt.timestamp() * 1000.0
    except Exception:
        return None


def collect_k6_output(
    k6_output_path: Path,
    run_set_dir: Path,
    scenario: str,
    run_set_id: str,
    run_id: str,
    raw_file_name: str | None = None,
    mode: str | None = None,
    config_hash: str | None = None,
    environment_hash: str | None = None,
    run_start_time: str | None = None,
) -> dict:
    """Convert k6 JSON output to per-sample raw JSONL.

    The canonical raw file name is raw-<scenario>-<mode>-<run_id>.jsonl.
    For single-mode scenarios mode defaults to the scenario name so the file
    prefix matches the Java BenchmarkRawWriter convention.

    Returns:
        {"samples_written": int, "raw_path": str}
    """
    effective_mode = mode or scenario
    raw_path = run_set_dir / (raw_file_name or f"raw-{scenario}-{effective_mode}-{run_id}.jsonl")

    sample_index = 0
    samples = 0
    with open(k6_output_path, "r", encoding="utf-8") as src, open(
        raw_path, "w", encoding="utf-8"
    ) as dst:
        for line in src:
            line = line.strip()
            if not line:
                continue
            record = json.loads(line)
            sample = k6_record_to_raw(
                record, scenario, run_set_id, run_id, sample_index, config_hash, environment_hash,
                run_start_time=run_start_time,
            )
            if sample is None:
                continue
            dst.write(json.dumps(sample, ensure_ascii=False))
            dst.write("\n")
            sample_index += 1
            samples += 1

    return {"samples_written": samples, "raw_path": str(raw_path)}


def _tag_number(tags: dict, key: str) -> float | None:
    value = tags.get(key)
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    try:
        return float(str(value))
    except (ValueError, TypeError):
        return None


def _tag_int(tags: dict, key: str) -> int | None:
    value = tags.get(key)
    if value is None:
        return None
    if isinstance(value, int):
        return value
    try:
        return int(str(value))
    except (ValueError, TypeError):
        return None


def k6_record_to_raw(record: dict, scenario: str, run_set_id: str, run_id: str,
                     sample_index: int, config_hash: str | None, environment_hash: str | None,
                     run_start_time: str | None = None) -> dict | None:
    """Convert a single k6 JSON record to a benchmark raw sample.

    Returns None for records that do not represent a request latency sample.
    """
    metric = record.get("metric")
    record_type = record.get("type")

    if metric != "bench_req_duration" or record_type != "Point":
        return None

    data = record.get("data", {})
    tags = data.get("tags", {})

    latency_ms = None
    value = data.get("value")
    if isinstance(value, (int, float)):
        latency_ms = float(value)

    status_code = _tag_int(tags, "status")
    success = None
    success_tag = tags.get("success")
    if success_tag is not None:
        success = str(success_tag).lower() in ("true", "1", "yes")

    timestamp = data.get("time", record.get("timestamp"))
    run_relative_time = None
    start_ms = _parse_iso_ms(run_start_time)
    sample_ms = _parse_iso_ms(timestamp)
    if start_ms is not None and sample_ms is not None:
        run_relative_time = sample_ms - start_ms

    k6_raw = deepcopy(record)
    k6_raw_tags = k6_raw.get("data", {}).get("tags", {})
    if isinstance(k6_raw_tags, dict):
        for key in list(k6_raw_tags.keys()):
            if key.lower() in SECRET_TAG_KEYS:
                k6_raw_tags[key] = "[REDACTED]"

    return {
        "timestamp": timestamp,
        "run_relative_time": run_relative_time,
        "scenario": scenario,
        "run_set_id": run_set_id,
        "run_id": run_id,
        "sample_index": sample_index,
        "metric_name": tags.get("metric_name", metric),
        "endpoint": tags.get("url"),
        "latency_ms": latency_ms,
        "ttft_ms": _tag_number(tags, "ttft_ms"),
        "status_code": status_code,
        "success": success,
        "error": tags.get("error"),
        "vu": _tag_int(tags, "vu"),
        "concurrency": _tag_int(tags, "concurrency"),
        "provider_mode": tags.get("provider_mode"),
        "phase": tags.get("phase"),
        "latency_measurement_source": tags.get("latency_measurement_source"),
        "latency_request_boundary": tags.get("latency_request_boundary"),
        "latency_pre_percentile_quantization": tags.get("latency_pre_percentile_quantization"),
        "config_hash": config_hash,
        "environment_hash": environment_hash,
        "k6_sample_reference": str(data.get("time", record.get("timestamp"))),
        "execution_metadata": {
            "k6_metric": metric,
            "k6_type": record_type,
            "k6_raw": k6_raw,
        },
        "t0_monotonic": _tag_number(tags, "t0_monotonic"),
        "t1_monotonic": _tag_number(tags, "t1_monotonic"),
        "qualifying_event_type": tags.get("qualifying_event_type"),
    }


def main(argv: list[str]) -> int:
    if len(argv) < 5:
        print(
            "Usage: k6_collector.py <k6_output.json> <run_set_dir> <scenario> <run_set_id> <run_id>",
            file=sys.stderr,
        )
        return 1

    k6_output_path = Path(argv[0])
    run_set_dir = Path(argv[1])
    scenario = argv[2]
    run_set_id = argv[3]
    run_id = argv[4]

    result = collect_k6_output(k6_output_path, run_set_dir, scenario, run_set_id, run_id)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
