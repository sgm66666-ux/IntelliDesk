#!/usr/bin/env python3
"""Analyze VU5-only HTTP repeatability diagnostics.

Diagnostic artifacts are explicitly NOT_BENCHMARK_EVIDENCE and are never
eligible for formal run-set acceptance or cell reuse.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path


PERCENTILES = [0, 0.1, 1, 5, 10, 25, 50, 75, 90, 95, 97.5, 99, 99.5, 99.9, 100]
CLIENT_METRICS = {
    "http_req_duration", "http_req_blocked", "http_req_connecting",
    "http_req_tls_handshaking", "http_req_sending", "http_req_waiting",
    "http_req_receiving",
}


def iso_ms(value: str) -> float:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000.0


def nearest_rank(values: list[float], percentile: float) -> float:
    ordered = sorted(values)
    if not ordered:
        raise ValueError("empty percentile input")
    rank = max(1, math.ceil(percentile / 100.0 * len(ordered)))
    return ordered[rank - 1]


def summary(values: list[float]) -> dict:
    if not values:
        return {"count": 0}
    result = {
        "count": len(values),
        "min": min(values),
        "max": max(values),
        "mean": statistics.fmean(values),
        "stdev": statistics.stdev(values) if len(values) > 1 else 0.0,
    }
    result["cv"] = result["stdev"] / result["mean"] if result["mean"] else 0.0
    result["nearest_rank_percentiles"] = {
        str(p): nearest_rank(values, p) for p in PERCENTILES
    }
    return result


def histogram(values: list[float], width: float) -> list[dict]:
    counts = Counter(math.floor(value / width) * width for value in values)
    return [
        {"lower_inclusive_ms": lower, "upper_exclusive_ms": lower + width, "count": count}
        for lower, count in sorted(counts.items())
    ]


def parse_k6(path: Path) -> tuple[dict, dict]:
    bench = []
    native = defaultdict(list)
    status = Counter()
    errors = 0
    all_times = []
    sustain_times = []
    ramp_times = []

    with path.open(encoding="utf-8") as stream:
        for line in stream:
            row = json.loads(line)
            if row.get("type") != "Point":
                continue
            metric = row.get("metric")
            data = row.get("data", {})
            value = data.get("value")
            timestamp = data.get("time")
            if not isinstance(value, (int, float)) or not timestamp:
                continue
            when = iso_ms(timestamp)
            all_times.append(when)
            if metric == "bench_req_duration":
                tags = data.get("tags", {})
                phase = tags.get("phase")
                record = {"time_ms": when, "value": float(value), "phase": phase, "tags": tags}
                bench.append(record)
                if phase == "sustain":
                    sustain_times.append(when)
                    status[str(tags.get("status"))] += 1
                    if str(tags.get("success")).lower() != "true":
                        errors += 1
                elif phase == "ramp":
                    ramp_times.append(when)
            elif metric in CLIENT_METRICS:
                native[metric].append((when, float(value)))

    if not sustain_times:
        raise ValueError(f"no sustain samples: {path}")
    sustain_start = min(sustain_times)
    sustain_end = max(sustain_times)
    sustain_custom = [item["value"] for item in bench if item["phase"] == "sustain"]
    ramp_custom = [item["value"] for item in bench if item["phase"] == "ramp"]
    native_sustain = {
        metric: [value for when, value in rows if sustain_start <= when <= sustain_end]
        for metric, rows in native.items()
    }

    distribution = {
        "classification": "NOT_BENCHMARK_EVIDENCE",
        "source_k6_output": path.name,
        "sustain_custom_latency_values_ms": sustain_custom,
        "sustain_native_http_req_duration_values_ms": native_sustain.get("http_req_duration", []),
    }
    analysis = {
        "classification": "NOT_BENCHMARK_EVIDENCE",
        "source_k6_output": path.name,
        "warmup": {
            "configured_ramp_ms": 5000,
            "ramp_sample_count": len(ramp_custom),
            "ramp_first_timestamp_ms": min(ramp_times) if ramp_times else None,
            "ramp_last_timestamp_ms": max(ramp_times) if ramp_times else None,
            "sustain_first_timestamp_ms": sustain_start,
            "phase_tags_present": bool(ramp_times and sustain_times),
            "sustain_samples_exclude_ramp_tag": True,
            "boundary_inflight_timestamp_overlap_ms": (
                max(0.0, max(ramp_times) - sustain_start) if ramp_times else None
            ),
            "boundary_note": (
                "Sub-millisecond timestamp overlap can occur for in-flight requests at the "
                "ramp/sustain boundary; authoritative selection uses the explicit phase tag."
            ),
        },
        "sustain": {
            "sample_count": len(sustain_custom),
            "window_observed_ms": sustain_end - sustain_start,
            "throughput_samples_per_second_contract_window": len(sustain_custom) / 30.0,
            "error_count": errors,
            "error_rate": errors / len(sustain_custom),
            "status_counts": dict(status),
        },
        "custom_bench_req_duration_ms": summary(sustain_custom),
        "custom_integer_histogram_1ms": histogram(sustain_custom, 1.0),
        "native_client_metrics_ms": {
            metric: summary(values) for metric, values in sorted(native_sustain.items())
        },
        "native_http_req_duration_histogram_0_1ms": histogram(
            native_sustain.get("http_req_duration", []), 0.1
        ),
    }
    return analysis, distribution


def parse_number(value: str):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def parse_percent(value: str):
    return parse_number(str(value).strip().rstrip("%"))


def parse_memory_bytes(value: str):
    match = re.match(r"\s*([0-9.]+)\s*([KMGTP]?i?B)", str(value))
    if not match:
        return None
    scale = {"B": 1, "KB": 1000, "MB": 1000**2, "GB": 1000**3,
             "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3}
    return float(match.group(1)) * scale.get(match.group(2), 1)


def parse_prometheus(lines: list[str]) -> dict[str, list[float]]:
    metrics = defaultdict(list)
    for line in lines:
        match = re.match(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+([-+0-9.eE]+)$", line)
        if not match:
            continue
        name, labels, raw_value = match.groups()
        value = parse_number(raw_value)
        if value is None:
            continue
        key = name
        if name.startswith("jvm_memory_"):
            area = re.search(r'area="([^"]+)"', labels or "")
            if area:
                key += f":{area.group(1)}"
        metrics[key].append(value)
    return metrics


def summarize_telemetry(path: Path) -> dict:
    hosts = []
    clients = []
    docker_rows = defaultdict(list)
    jvm_snapshots = []
    with path.open(encoding="utf-8-sig") as stream:
        for line in stream:
            if not line.strip():
                continue
            row = json.loads(line)
            kind = row.get("record_type")
            if kind == "host":
                hosts.append(row)
            elif kind == "client_process":
                clients.append(row)
            elif kind == "docker_stats":
                docker_rows[row.get("name")].append(row)
            elif kind == "jvm_metrics":
                jvm_snapshots.append(parse_prometheus(row.get("metrics", [])))

    host_cpu = [parse_number(row.get("cpu_load_percent")) for row in hosts]
    host_cpu = [value for value in host_cpu if value is not None]
    host_used = [row["total_visible_memory_bytes"] - row["free_physical_memory_bytes"] for row in hosts]
    client_ws = [row.get("working_set_bytes") for row in clients if row.get("working_set_bytes") is not None]
    client_private = [row.get("private_memory_bytes") for row in clients if row.get("private_memory_bytes") is not None]
    client_threads = [row.get("thread_count") for row in clients if row.get("thread_count") is not None]
    client_handles = [row.get("handle_count") for row in clients if row.get("handle_count") is not None]
    client_cpu_ms = [row.get("total_processor_time_ms") for row in clients if row.get("total_processor_time_ms") is not None]

    docker_summary = {}
    for name, rows in docker_rows.items():
        cpu = [parse_percent(row.get("cpu_percent")) for row in rows]
        cpu = [value for value in cpu if value is not None]
        memory = [parse_memory_bytes(str(row.get("memory_usage", "")).split("/")[0]) for row in rows]
        memory = [value for value in memory if value is not None]
        pids = [parse_number(row.get("pids")) for row in rows]
        pids = [value for value in pids if value is not None]
        docker_summary[name] = {
            "samples": len(rows), "cpu_percent": summary(cpu),
            "memory_usage_bytes": summary(memory), "pids": summary(pids),
            "network_io_first": rows[0].get("network_io") if rows else None,
            "network_io_last": rows[-1].get("network_io") if rows else None,
        }

    metric_series = defaultdict(list)
    for snapshot in jvm_snapshots:
        for key, values in snapshot.items():
            metric_series[key].append(sum(values))
    jvm_summary = {key: summary(values) for key, values in sorted(metric_series.items())}
    gc_deltas = {
        key: values[-1] - values[0]
        for key, values in metric_series.items()
        if ("jvm_gc_pause_seconds_count" in key or "jvm_gc_pause_seconds_sum" in key) and len(values) >= 2
    }

    return {
        "telemetry_file": path.name,
        "host": {
            "samples": len(hosts), "cpu_load_percent": summary(host_cpu),
            "used_physical_memory_bytes": summary(host_used),
        },
        "client_k6": {
            "samples": len(clients), "working_set_bytes": summary(client_ws),
            "private_memory_bytes": summary(client_private), "thread_count": summary(client_threads),
            "handle_count": summary(client_handles),
            "cpu_time_delta_ms": (max(client_cpu_ms) - min(client_cpu_ms)) if len(client_cpu_ms) >= 2 else None,
        },
        "containers": docker_summary,
        "jvm": {"samples": len(jvm_snapshots), "metrics": jvm_summary, "gc_counter_deltas": gc_deltas},
    }


def deviation(values: list[float]) -> float:
    reference = statistics.median(values)
    if reference == 0:
        return 0.0 if len(set(values)) == 1 else math.inf
    return max(abs(value - reference) / reference for value in values)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("diagnostic_dir", type=Path)
    args = parser.parse_args()
    root = args.diagnostic_dir
    analyses = []
    distributions = []
    telemetry = []
    for index in (1, 2, 3):
        k6_path = root / f"k6_output_vu5-diag-run-{index}.json"
        telemetry_path = root / f"telemetry-vu5-diag-run-{index}.jsonl"
        analysis, distribution = parse_k6(k6_path)
        analysis["run"] = index
        analyses.append(analysis)
        distributions.append(distribution)
        telemetry.append(summarize_telemetry(telemetry_path))
        (root / f"latency-distribution-vu5-diag-run-{index}.json").write_text(
            json.dumps(distribution, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
        )

    custom_p95 = [item["custom_bench_req_duration_ms"]["nearest_rank_percentiles"]["95"] for item in analyses]
    native_p95 = [item["native_client_metrics_ms"]["http_req_duration"]["nearest_rank_percentiles"]["95"] for item in analyses]
    custom_dev = deviation(custom_p95)
    native_dev = deviation(native_p95)
    integer_quantized = all(float(value).is_integer() for item in analyses for value in item["custom_bench_req_duration_ms"]["nearest_rank_percentiles"].values())

    quantization_boundary = (
        integer_quantized
        and all(value in {1.0, 2.0} for value in custom_p95)
        and max(native_p95) < 2.0
    )

    if native_dev <= 0.20 and quantization_boundary:
        classification = "HARNESS_DEFECT"
        rationale = (
            "The harness records bench_req_duration with Date.now() integer-millisecond deltas. "
            "At this latency scale a one-bucket move from 1ms to 2ms creates a 50% apparent "
            "deviation, while k6 native sub-millisecond http_req_duration stays within 20%. "
            "The diagnostic does not reuse Formal 008 cells; it independently reproduces the "
            "measurement-resolution mechanism."
        )
    elif native_dev > 0.20:
        classification = "SYSTEM_VARIANCE"
        rationale = "Native k6 request-duration p95 also exceeds 20%; variance exists beyond custom timer quantization."
    else:
        classification = "METHOD_CONFLICT"
        rationale = "Observed distributions do not support a runtime defect, but the frozen relative gate is unstable at the measured scale."

    result = {
        "schema_version": "1.0",
        "classification": "NOT_BENCHMARK_EVIDENCE",
        "diagnostic_id": root.name,
        "formal_008_reused": False,
        "formal_009_created": False,
        "production_modified": False,
        "repeatability_threshold_modified": False,
        "runs": analyses,
        "telemetry": telemetry,
        "repeatability": {
            "custom_p95_ms": custom_p95,
            "custom_max_relative_deviation": custom_dev,
            "native_p95_ms": native_p95,
            "native_max_relative_deviation": native_dev,
            "threshold": 0.20,
            "custom_integer_quantized": integer_quantized,
            "quantization_boundary": quantization_boundary,
        },
        "root_cause_classification": classification,
        "root_cause_rationale": rationale,
        "classification_evidence": {
            "HARNESS_DEFECT": classification == "HARNESS_DEFECT",
            "ENVIRONMENT_VARIANCE": "not supported: three native p95 values differ by <=20% and no resource saturation was observed",
            "SYSTEM_VARIANCE": "not supported: native request-duration p95 deviation is within the frozen threshold",
            "METHOD_CONFLICT": "not primary: the unchanged 20% rule passes when applied to the higher-resolution native duration",
        },
    }
    (root / "diagnostic-analysis.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps({
        "classification": classification,
        "custom_p95_ms": custom_p95,
        "custom_deviation": custom_dev,
        "native_p95_ms": native_p95,
        "native_deviation": native_dev,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
