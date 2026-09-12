#!/usr/bin/env python3
"""Analyze diagnostics-only RERANK-c4 raw and synchronized runtime telemetry.

The output is diagnostic metadata only and must never be consumed by Formal
benchmark acceptance.
"""

import argparse
import json
import math
import statistics
from datetime import datetime, timedelta, timezone
from pathlib import Path


def parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def parse_duration(value: str) -> float:
    if not value.startswith("PT") or not value.endswith("S"):
        raise ValueError(f"unsupported ISO duration: {value}")
    return float(value[2:-1])


def nearest_rank(values: list[float], percentile: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(percentile * len(ordered)) - 1)]


def percentile_summary(values: list[float]) -> dict:
    return {
        "p50": nearest_rank(values, 0.50),
        "p95": nearest_rank(values, 0.95),
        "p99": nearest_rank(values, 0.99),
        "max": max(values),
    }


def load_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def process_role_summary(records: list[dict], role: str, start: datetime, end: datetime,
                         logical_processors: int) -> dict | None:
    selected = [
        record for record in records
        if record.get("record_type") == "process"
        and record.get("role") == role
        and start <= parse_time(record["observed_at"]) <= end
    ]
    if not selected:
        return None
    by_pid: dict[int, list[dict]] = {}
    for record in selected:
        by_pid.setdefault(int(record["process_id"]), []).append(record)
    pid, samples = max(by_pid.items(), key=lambda item: len(item[1]))
    samples.sort(key=lambda item: parse_time(item["observed_at"]))
    one_core_loads = []
    host_loads = []
    for previous, current in zip(samples, samples[1:]):
        previous_cpu = previous.get("total_processor_time_ms")
        current_cpu = current.get("total_processor_time_ms")
        elapsed_ms = (
            parse_time(current["observed_at"]) - parse_time(previous["observed_at"])
        ).total_seconds() * 1000.0
        if previous_cpu is None or current_cpu is None or elapsed_ms <= 0:
            continue
        one_core = max(0.0, (float(current_cpu) - float(previous_cpu)) / elapsed_ms * 100.0)
        one_core_loads.append(one_core)
        host_loads.append(one_core / logical_processors)
    return {
        "process_id": pid,
        "samples": len(samples),
        "one_core_cpu_pct_mean": statistics.mean(one_core_loads) if one_core_loads else None,
        "one_core_cpu_pct_max": max(one_core_loads) if one_core_loads else None,
        "host_capacity_cpu_pct_mean": statistics.mean(host_loads) if host_loads else None,
        "host_capacity_cpu_pct_max": max(host_loads) if host_loads else None,
        "working_set_bytes_min": min(int(item["working_set_bytes"]) for item in samples),
        "working_set_bytes_max": max(int(item["working_set_bytes"]) for item in samples),
        "private_memory_bytes_min": min(int(item["private_memory_bytes"]) for item in samples),
        "private_memory_bytes_max": max(int(item["private_memory_bytes"]) for item in samples),
        "thread_count_max": max(int(item["thread_count"]) for item in samples),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("run_set_dir", type=Path)
    parser.add_argument("telemetry_jsonl", type=Path)
    parser.add_argument("jfr_events_json", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    manifest = json.loads((args.run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
    frozen = json.loads((args.run_set_dir / manifest["frozen_config_file"]).read_text(encoding="utf-8"))
    if manifest.get("evidence_purpose") != "SMOKE" \
            or frozen.get("performance_config", {}).get("not_benchmark_evidence") is not True:
        raise ValueError("diagnostic evidence boundary missing")

    telemetry = load_jsonl(args.telemetry_jsonl)
    host_records = [item for item in telemetry if item.get("record_type") == "host"]
    logical_processors = int(host_records[0]["logical_processors"])
    jfr_payload = json.loads(args.jfr_events_json.read_text(encoding="utf-8"))
    events = jfr_payload["recording"]["events"]
    gc_events = [item for item in events if item["type"] == "jdk.GCPhasePause"]
    cpu_events = [item for item in events if item["type"] == "jdk.CPULoad"]

    run_summaries = []
    all_raw_tagged = True
    for run in manifest["runs"]:
        rows = load_jsonl(args.run_set_dir / run["raw_file"])
        latencies = [float(row["latency_ms"]) for row in rows]
        all_raw_tagged = all_raw_tagged and all(
            row.get("execution_metadata", {}).get("evidence_tag") == "NOT_BENCHMARK_EVIDENCE"
            for row in rows
        )
        operation_intervals = []
        for row in rows:
            end = parse_time(row["timestamp"])
            operation_intervals.append((end - timedelta(milliseconds=float(row["latency_ms"])), end))
        start = min(interval[0] for interval in operation_intervals)
        end = max(interval[1] for interval in operation_intervals)

        run_gc = []
        for event in gc_events:
            event_start = parse_time(event["values"]["startTime"])
            duration = parse_duration(event["values"]["duration"])
            event_end = event_start + timedelta(seconds=duration)
            if event_end >= start and event_start <= end:
                run_gc.append((event_start, event_end, duration))
        gc_overlap_latencies = []
        non_gc_latencies = []
        for row, interval in zip(rows, operation_intervals):
            overlaps = any(gc_end >= interval[0] and gc_start <= interval[1]
                           for gc_start, gc_end, _ in run_gc)
            (gc_overlap_latencies if overlaps else non_gc_latencies).append(float(row["latency_ms"]))

        run_cpu = [
            event["values"] for event in cpu_events
            if start <= parse_time(event["values"]["startTime"]) <= end
        ]
        deciles = []
        for index in range(10):
            part = latencies[index * len(latencies) // 10:(index + 1) * len(latencies) // 10]
            deciles.append({"decile": index + 1, **percentile_summary(part)})
        hosts = [
            item for item in host_records
            if start <= parse_time(item["observed_at"]) <= end
        ]
        observation = json.loads(
            (args.run_set_dir / run["run_observation_file"]).read_text(encoding="utf-8")
        )
        run_summaries.append({
            "run_id": run["run_id"],
            "sample_count": len(rows),
            "errors": sum(1 for row in rows if not row.get("success")),
            "window_start": start.isoformat(),
            "window_end": end.isoformat(),
            "warmup_completed_samples": observation["warmup_completed_samples"],
            "warmup_operation_duration_ms": observation["warmup_operation_duration_ms"],
            "percentiles": percentile_summary(latencies),
            "deciles": deciles,
            "samples_over_250ms": sum(1 for value in latencies if value > 250.0),
            "gc_pause_count": len(run_gc),
            "gc_pause_total_ms": sum(item[2] for item in run_gc) * 1000.0,
            "gc_pause_max_ms": max((item[2] for item in run_gc), default=0.0) * 1000.0,
            "gc_overlap_sample_count": len(gc_overlap_latencies),
            "gc_overlap_latency": percentile_summary(gc_overlap_latencies)
            if gc_overlap_latencies else None,
            "non_gc_latency": percentile_summary(non_gc_latencies),
            "jfr_machine_cpu_mean_pct": statistics.mean(float(item["machineTotal"]) for item in run_cpu) * 100.0
            if run_cpu else None,
            "jfr_machine_cpu_max_pct": max(float(item["machineTotal"]) for item in run_cpu) * 100.0
            if run_cpu else None,
            "jfr_jvm_cpu_mean_pct": statistics.mean(
                float(item["jvmUser"]) + float(item["jvmSystem"]) for item in run_cpu
            ) * 100.0 if run_cpu else None,
            "jfr_jvm_cpu_max_pct": max(
                float(item["jvmUser"]) + float(item["jvmSystem"]) for item in run_cpu
            ) * 100.0 if run_cpu else None,
            "host_free_memory_bytes_min": min(
                (int(item["free_physical_memory_bytes"]) for item in hosts), default=None
            ),
            "processes": {
                role: process_role_summary(telemetry, role, start, end, logical_processors)
                for role in ("java", "reranker", "ollama")
            },
        })

    p50_values = [run["percentiles"]["p50"] for run in run_summaries]
    p95_values = [run["percentiles"]["p95"] for run in run_summaries]
    mean_p50 = statistics.mean(p50_values)
    median_p95 = statistics.median(p95_values)
    result = {
        "classification": "NOT_BENCHMARK_EVIDENCE",
        "formal_acceptance_use_prohibited": True,
        "run_set_id": manifest["run_set_id"],
        "config_hash": manifest["config_hash"],
        "all_raw_samples_tagged_not_benchmark_evidence": all_raw_tagged,
        "runs": run_summaries,
        "diagnostic_repeatability": {
            "p50_cv_pct": statistics.pstdev(p50_values) / mean_p50 * 100.0,
            "p95_values": p95_values,
            "p95_median": median_p95,
            "p95_max_deviation_pct": max(abs(value - median_p95) / median_p95 for value in p95_values) * 100.0,
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

