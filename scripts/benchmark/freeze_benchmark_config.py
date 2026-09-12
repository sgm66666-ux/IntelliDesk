#!/usr/bin/env python3
"""Freeze a Wave 2 benchmark execution config artifact.

Produces a canonical freeze file with stable config_hash and environment_hash.
The canonical JSON uses sorted keys and compact separators so that the same
logical config always yields the same SHA-256 hash.

No timestamp, PID, random value, absolute path, run-set-id, or log path enters
the canonical config or environment identity hash.
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

from environment_identity import collect_environment_identity


def _canonical_json(value: dict) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def _sha256_hex(value: dict) -> str:
    return hashlib.sha256(_canonical_json(value).encode("utf-8")).hexdigest()


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_benchmark_harness_identity(performance_config: dict) -> dict:
    """Build a stable, source-linked benchmark behavior identity.

    This identity is evidence linkage, not a second acceptance decision. Its
    behavior hash binds the scenario timing contract to the scripts that emit,
    collect, finalize, and independently validate canonical samples.
    """
    script_dir = Path(__file__).resolve().parent
    component_paths = {
        "k6_script_template_sha256": script_dir / "k6_script_template.js",
        "k6_runner_sha256": script_dir / "k6_runner.py",
        "k6_collector_sha256": script_dir / "k6_collector.py",
        "percentile_sha256": script_dir / "percentile.py",
        "orchestrator_sha256": script_dir / "orchestrate_formal_run.py",
        "validator_sha256": script_dir / "run_set_acceptance.py",
    }
    components = {name: _file_sha256(path) for name, path in component_paths.items()}
    contract_fields = (
        "authoritative_latency_semantics",
        "latency_measurement_source",
        "latency_measurement_unit",
        "latency_request_boundary",
        "latency_pre_percentile_quantization",
        "percentile_method",
        "sample_collection_semantics",
    )
    payload = {
        "schema_version": "1.0",
        "scenario": performance_config["scenario"],
        "behavior_contract": {
            field: performance_config[field]
            for field in contract_fields
            if field in performance_config
        },
        "components": components,
    }
    return {**payload, "behavior_hash": _sha256_hex(payload)}


def build_performance_config(
    scenario: str,
    target: str,
    http_method: str,
    request_body_identity: str | None,
    duration: str,
    vus: int,
    warmup_policy: str,
    iteration_rate_policy: str,
    timeout_ms: int,
    thresholds: dict,
    expected_status: int,
    success_semantics: str,
    sample_collection_semantics: str,
    percentile_method: str,
    auth_mode: str = "none",
    auth_source: str | None = None,
    content_type: str | None = None,
    extra_headers: dict | None = None,
    ttft_mode: bool = False,
    ttft_topology_mode: str | None = None,
    helper_bind_address: str | None = None,
    helper_port: int | None = None,
    helper_concurrency_model: str | None = None,
    helper_readiness_path: str | None = None,
    helper_startup_timeout_ms: int | None = None,
    helper_request_timeout_margin_ms: int | None = None,
    helper_request_queue_size: int | None = None,
    ttft_t0_t1_semantics_version: str | None = None,
    api_key_benchmark_methodology: str | None = None,
    api_key_baseline_mode: str | None = None,
    api_key_test_mode: str | None = None,
    api_key_target_logical_identity: str | None = None,
    ramp_duration: str | None = None,
    sustain_duration: str | None = None,
    extra_metadata: dict | None = None,
    authoritative_latency_semantics: str | None = None,
    application_rate_limit_policy: str | None = None,
    provider_boundary: str | None = None,
    conversation_isolation: str | None = None,
    post_ttft_settle_delay_ms: int | None = None,
    latency_measurement_source: str | None = None,
    latency_measurement_unit: str | None = None,
    latency_request_boundary: str | None = None,
    latency_pre_percentile_quantization: str | None = None,
) -> dict:
    if not scenario or not scenario.strip():
        raise ValueError("scenario must not be blank")
    if not target or not target.strip():
        raise ValueError("target must not be blank")
    if not http_method or not http_method.strip():
        raise ValueError("http_method must not be blank")
    if not duration or not duration.strip():
        raise ValueError("duration must not be blank")
    if vus < 1:
        raise ValueError("vus must be >= 1")
    if timeout_ms < 1:
        raise ValueError("timeout_ms must be >= 1")
    if not success_semantics or not success_semantics.strip():
        raise ValueError("success_semantics must not be blank")
    if not sample_collection_semantics or not sample_collection_semantics.strip():
        raise ValueError("sample_collection_semantics must not be blank")
    if not percentile_method or not percentile_method.strip():
        raise ValueError("percentile_method must not be blank")
    if auth_mode not in ("none", "session_cookie", "api_key_header", "bearer_header"):
        raise ValueError(f"unsupported auth_mode: {auth_mode}")

    config = {
        "schema_version": "1.0",
        "scenario": scenario,
        "target": target,
        "http_method": http_method,
        "duration": duration,
        "ramp_duration": ramp_duration or "5s",
        "sustain_duration": sustain_duration or "30s",
        "vus": vus,
        "warmup_policy": warmup_policy,
        "iteration_rate_policy": iteration_rate_policy,
        "timeout_ms": timeout_ms,
        "thresholds": dict(thresholds) if thresholds else {},
        "expected_status": expected_status,
        "success_semantics": success_semantics,
        "sample_collection_semantics": sample_collection_semantics,
        "percentile_method": percentile_method,
        "auth_mode": auth_mode,
        "ttft_mode": ttft_mode,
    }
    if request_body_identity:
        config["request_body_identity"] = request_body_identity
    if auth_source:
        config["auth_source"] = auth_source
    if content_type:
        config["content_type"] = content_type
    if extra_headers:
        config["extra_headers"] = dict(extra_headers)
    if ttft_topology_mode:
        config["ttft_topology_mode"] = ttft_topology_mode
    if helper_bind_address:
        config["helper_bind_address"] = helper_bind_address
    if helper_port is not None:
        config["helper_port"] = helper_port
    if helper_concurrency_model:
        config["helper_concurrency_model"] = helper_concurrency_model
    if helper_readiness_path:
        config["helper_readiness_path"] = helper_readiness_path
    if helper_startup_timeout_ms is not None:
        config["helper_startup_timeout_ms"] = helper_startup_timeout_ms
    if helper_request_timeout_margin_ms is not None:
        config["helper_request_timeout_margin_ms"] = helper_request_timeout_margin_ms
    if helper_request_queue_size is not None:
        if helper_request_queue_size < 1:
            raise ValueError("helper_request_queue_size must be >= 1")
        config["helper_request_queue_size"] = helper_request_queue_size
    if ttft_t0_t1_semantics_version:
        config["ttft_t0_t1_semantics_version"] = ttft_t0_t1_semantics_version
    if api_key_benchmark_methodology:
        config["api_key_benchmark_methodology"] = api_key_benchmark_methodology
    if api_key_baseline_mode:
        config["api_key_baseline_mode"] = api_key_baseline_mode
    if api_key_test_mode:
        config["api_key_test_mode"] = api_key_test_mode
    if api_key_target_logical_identity:
        config["api_key_target_logical_identity"] = api_key_target_logical_identity
    if authoritative_latency_semantics:
        config["authoritative_latency_semantics"] = authoritative_latency_semantics
    if application_rate_limit_policy:
        config["application_rate_limit_policy"] = application_rate_limit_policy
    if provider_boundary:
        config["provider_boundary"] = provider_boundary
    if conversation_isolation:
        config["conversation_isolation"] = conversation_isolation
    if post_ttft_settle_delay_ms is not None:
        if post_ttft_settle_delay_ms < 0:
            raise ValueError("post_ttft_settle_delay_ms must be >= 0")
        config["post_ttft_settle_delay_ms"] = post_ttft_settle_delay_ms
    if latency_measurement_source:
        config["latency_measurement_source"] = latency_measurement_source
    if latency_measurement_unit:
        config["latency_measurement_unit"] = latency_measurement_unit
    if latency_request_boundary:
        config["latency_request_boundary"] = latency_request_boundary
    if latency_pre_percentile_quantization:
        config["latency_pre_percentile_quantization"] = latency_pre_percentile_quantization
    if extra_metadata:
        config["metadata"] = dict(extra_metadata)
    return config


def build_application_identity(backend_commit: str | None = None) -> dict:
    return {"backend_commit": backend_commit or "unknown"}


def config_id(config_hash: str) -> str:
    return config_hash[:16].lower()


def freeze_execution_config(
    performance_config: dict,
    environment_identity: dict,
    application_identity: dict,
    k6_version: str,
    output_path: Path,
) -> str:
    config_hash = _sha256_hex(performance_config)
    environment_hash = _sha256_hex(environment_identity)

    freeze = {
        "schema_version": "1.0",
        "scenario": performance_config["scenario"],
        "config_hash": config_hash,
        "config_id": config_id(config_hash),
        "environment_hash": environment_hash,
        "k6_version": k6_version,
        "frozen_at": None,  # will be set just before writing
        "application_identity": application_identity,
        "environment_identity": environment_identity,
        "performance_config": performance_config,
        "benchmark_harness_identity": build_benchmark_harness_identity(performance_config),
    }

    from datetime import datetime, timezone
    freeze["frozen_at"] = datetime.now(timezone.utc).isoformat()

    output_path.parent.mkdir(parents=True, exist_ok=True)
    if output_path.exists():
        raise FileExistsError(f"FAIL_IF_EXISTS: {output_path}")
    fd, temporary_name = tempfile.mkstemp(prefix=output_path.name + ".tmp-", dir=output_path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(freeze, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary_name, output_path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)
    return config_hash


def _scenario_definition(name: str) -> dict:
    """Return the plan-approved scenario definition for a named scenario.

    v1.2 amendment: rag-pipeline uses bearer_header with logical target template,
    TTFT topology, and helper lifecycle fields. api-key-auth uses paired
    comparison methodology. The 2026-09-10 HTTP Concurrency amendment changes
    only its canonical direct-request timing precision/source.
    """
    base = {
        "rag-pipeline": {
            "target": "/api/workspaces/{workspace_id}/conversations/{conversation_id}/messages/stream",
            "http_method": "POST",
            "request_body_identity": "fixed-rag-prompt-v1",
            "auth_mode": "bearer_header",
            "auth_source": "BENCH_ACCESS_TOKEN",
            "content_type": "application/json",
            "extra_headers": {"Accept": "text/event-stream"},
            "ttft_mode": True,
            "ttft_topology_mode": "k6 -> helper -> localhost:80 -> Nginx -> backend",
            "helper_bind_address": "127.0.0.1",
            "helper_port": 18181,
            "helper_concurrency_model": "threaded",
            "helper_readiness_path": "/health",
            "helper_startup_timeout_ms": 30000,
            "helper_request_timeout_margin_ms": 5000,
            "helper_request_queue_size": 128,
            "ttft_t0_t1_semantics_version": "1.0",
            "duration": "30s",
            "ramp_duration": "5s",
            "sustain_duration": "30s",
            "vus": 10,
            "warmup_policy": "5s_ramp_30s_sustain",
            "iteration_rate_policy": "closed-model-ramping-vus",
            "authoritative_latency_semantics": "first_non_empty_token_ttft_ms",
            "application_rate_limit_policy": "disabled_for_controlled_performance_measurement",
            "provider_boundary": "deterministic_e2e_stub_at_provider_boundary_real_system_pipeline",
            "conversation_isolation": "one_conversation_per_k6_vu",
            "post_ttft_settle_delay_ms": 200,
            "timeout_ms": 30000,
            "thresholds": {"p50": 200, "p90": 500, "p95": 1000, "p99": 2000, "error_rate": 0.01},
            "expected_status": 200,
            "success_semantics": "HTTP 200 and first non-empty token delta received (TTFT)",
            "sample_collection_semantics": "all bench_req_duration Point samples in sustain stage",
            "percentile_method": "nearest-rank",
        },
        "http-concurrency": {
            "target": "/api/health",
            "http_method": "GET",
            "request_body_identity": None,
            "auth_mode": "none",
            "auth_source": None,
            "content_type": "application/json",
            "extra_headers": {},
            "ttft_mode": False,
            "duration": "30s",
            "ramp_duration": "5s",
            "sustain_duration": "30s",
            "vus": 100,
            "warmup_policy": "5s_ramp_30s_sustain",
            "iteration_rate_policy": "closed-model-ramping-vus",
            "authoritative_latency_semantics": "request_duration_ms",
            "latency_measurement_source": "k6_response_timings_duration",
            "latency_measurement_unit": "fractional_milliseconds",
            "latency_request_boundary": "sending_plus_waiting_plus_receiving_excludes_blocked_dns_connect_tls",
            "latency_pre_percentile_quantization": "none",
            "application_rate_limit_policy": "not_applicable_public_health_path",
            "timeout_ms": 5000,
            "thresholds": {"p50": 50, "p90": 100, "p95": 200, "p99": 500, "error_rate": 0.0},
            "expected_status": 200,
            "success_semantics": "HTTP 200 from backend /api/health via Nginx",
            "sample_collection_semantics": "all bench_req_duration Point samples in sustain stage",
            "percentile_method": "nearest-rank",
        },
        "api-key-auth": {
            "target": "/api/workspaces/{workspace_id}/knowledge-bases",
            "http_method": "GET",
            "request_body_identity": None,
            "auth_mode": "api_key_header",
            "auth_source": "BENCH_API_KEY_SECRET",
            "content_type": "application/json",
            "extra_headers": {},
            "ttft_mode": False,
            "duration": "30s",
            "ramp_duration": "5s",
            "sustain_duration": "30s",
            "api_key_benchmark_methodology": "paired_comparison",
            "api_key_baseline_mode": "bearer-baseline",
            "api_key_test_mode": "api-key",
            "api_key_target_logical_identity": "/api/workspaces/{workspace_id}/knowledge-bases",
            "vus": 10,
            "warmup_policy": "5s_ramp_30s_sustain",
            "iteration_rate_policy": "closed-model-ramping-vus",
            "authoritative_latency_semantics": "request_duration_ms",
            "application_rate_limit_policy": "disabled_for_controlled_performance_measurement",
            "timeout_ms": 10000,
            "thresholds": {"p50": 100, "p90": 300, "p95": 500, "p99": 1000, "error_rate": 0.0},
            "expected_status": 200,
            "success_semantics": "HTTP 200 on API-key-authenticated protected resource (auth overhead only)",
            "sample_collection_semantics": "all bench_req_duration Point samples in sustain stage",
            "percentile_method": "nearest-rank",
        },
    }
    if name not in base:
        raise ValueError(f"unknown scenario: {name}; known scenarios: {list(base.keys())}")
    return base[name]


def _detect_k6_version() -> str:
    candidates = []
    k6_path = os.environ.get("K6_PATH")
    if k6_path:
        candidates.append(k6_path)
    candidates.extend([
        r"C:\tools\k6\k6-v2.2.0-windows-amd64\k6.exe",
        r"C:\tools\k6\k6.exe",
    ])
    from shutil import which
    from_path = which("k6")
    if from_path:
        candidates.append(from_path)
    for candidate in candidates:
        if not Path(candidate).exists():
            continue
        try:
            result = subprocess.run([candidate, "version"], capture_output=True, text=True, timeout=10)
            if result.returncode == 0:
                return result.stdout.strip().splitlines()[0]
        except Exception:
            pass
    return "unknown"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Freeze a Wave 2 benchmark execution config")
    parser.add_argument("--scenario", required=True, help="Scenario name to freeze")
    parser.add_argument("--k6-version", default=_detect_k6_version(), help="k6 version string")
    parser.add_argument("--output", "-o", required=True, help="Output freeze file path")
    parser.add_argument("--backend-commit", default="unknown", help="Backend commit identity")
    args = parser.parse_args(argv)

    definition = _scenario_definition(args.scenario)
    performance_config = build_performance_config(
        scenario=args.scenario,
        target=definition["target"],
        http_method=definition["http_method"],
        request_body_identity=definition.get("request_body_identity"),
        duration=definition["duration"],
        vus=definition["vus"],
        warmup_policy=definition["warmup_policy"],
        iteration_rate_policy=definition["iteration_rate_policy"],
        timeout_ms=definition["timeout_ms"],
        thresholds=definition["thresholds"],
        expected_status=definition["expected_status"],
        success_semantics=definition["success_semantics"],
        sample_collection_semantics=definition["sample_collection_semantics"],
        percentile_method=definition["percentile_method"],
        auth_mode=definition.get("auth_mode", "none"),
        auth_source=definition.get("auth_source"),
        content_type=definition.get("content_type"),
        extra_headers=definition.get("extra_headers"),
        ttft_mode=definition.get("ttft_mode", False),
        ttft_topology_mode=definition.get("ttft_topology_mode"),
        helper_bind_address=definition.get("helper_bind_address"),
        helper_port=definition.get("helper_port"),
        helper_concurrency_model=definition.get("helper_concurrency_model"),
        helper_readiness_path=definition.get("helper_readiness_path"),
        helper_startup_timeout_ms=definition.get("helper_startup_timeout_ms"),
        helper_request_timeout_margin_ms=definition.get("helper_request_timeout_margin_ms"),
        helper_request_queue_size=definition.get("helper_request_queue_size"),
        ttft_t0_t1_semantics_version=definition.get("ttft_t0_t1_semantics_version"),
        api_key_benchmark_methodology=definition.get("api_key_benchmark_methodology"),
        api_key_baseline_mode=definition.get("api_key_baseline_mode"),
        api_key_test_mode=definition.get("api_key_test_mode"),
        api_key_target_logical_identity=definition.get("api_key_target_logical_identity"),
        ramp_duration=definition.get("ramp_duration"),
        sustain_duration=definition.get("sustain_duration"),
        authoritative_latency_semantics=definition.get("authoritative_latency_semantics"),
        application_rate_limit_policy=definition.get("application_rate_limit_policy"),
        provider_boundary=definition.get("provider_boundary"),
        conversation_isolation=definition.get("conversation_isolation"),
        post_ttft_settle_delay_ms=definition.get("post_ttft_settle_delay_ms"),
        latency_measurement_source=definition.get("latency_measurement_source"),
        latency_measurement_unit=definition.get("latency_measurement_unit"),
        latency_request_boundary=definition.get("latency_request_boundary"),
        latency_pre_percentile_quantization=definition.get("latency_pre_percentile_quantization"),
    )

    environment_identity = collect_environment_identity()
    application_identity = build_application_identity(args.backend_commit)

    config_hash = freeze_execution_config(
        performance_config,
        environment_identity,
        application_identity,
        args.k6_version,
        Path(args.output),
    )

    result = {
        "status": "PASS",
        "scenario": args.scenario,
        "config_hash": config_hash,
        "config_id": config_id(config_hash),
        "output_path": args.output,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
