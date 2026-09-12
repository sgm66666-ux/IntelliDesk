#!/usr/bin/env python3
"""Run-set acceptance validation for Phase 8 Wave 2 benchmark evidence.

Distinguishes "execution finished" from "final acceptance eligible".  A run-set
is eligible only when the measurement data itself satisfies the approved plan
and methodology, independent of any manifest status written by the runner.

Validation is read-only: it never modifies manifests, raw files, or configs.
"""

import json
import hashlib
import math
import re
import statistics
from pathlib import Path
from typing import Any

from percentile import compute_percentiles
from retrieval_benchmark_contract import (
    PERFORMANCE_CONFIG_EXTENSION,
    validate_measured_run as validate_retrieval_measured_run,
    validate_preflight as validate_retrieval_preflight,
    validate_run_observation as validate_retrieval_run_observation,
    validate_sample_metadata as validate_retrieval_sample_metadata,
)


# External k6 scenarios use phase-provenanced sustain-only authoritative raw.
EXTERNAL_K6_SCENARIOS = {"rag-pipeline", "http-concurrency", "api-key-auth"}
REQUIRED_EXTERNAL_MODES = {
    "rag-pipeline": {"rag-pipeline"},
    "http-concurrency": {"http-concurrency"},
    "api-key-auth": {"bearer-baseline", "api-key"},
}

# Scenarios that are required to exercise VU 1/5/10 concurrency levels.
MANDATORY_VU_LEVELS = {1, 5, 10}
MIN_RUNS_PER_VU_LEVEL = 3
EXTERNAL_VU_SCENARIOS = EXTERNAL_K6_SCENARIOS
AUTHORITATIVE_LATENCY_METRIC = "bench_req_duration"
HTTP_CONCURRENCY_LATENCY_CONTRACT = {
    "latency_measurement_source": "k6_response_timings_duration",
    "latency_measurement_unit": "fractional_milliseconds",
    "latency_request_boundary": "sending_plus_waiting_plus_receiving_excludes_blocked_dns_connect_tls",
    "latency_pre_percentile_quantization": "none",
}
VALID_EXTERNAL_PHASES = {"ramp", "sustain", "cooldown"}
JAVA_SCHEMA_VERSION = "1.1"
JAVA_CONTRACTS = {"COMPONENT", "B_CLASS"}
FORMAL_JAVA_PURPOSE = "FORMAL_BENCHMARK_CANDIDATE"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

JAVA_SCENARIOS = {
    "retrieval": {
        "contract": "COMPONENT",
        "modes": {f"{mode}-c{concurrency}" for mode in ("VECTOR", "BM25", "HYBRID", "RERANK") for concurrency in (1, 4, 8)},
        "metric": None,
        "samples": 1000,
        "duration_ms": 5000,
        "max_duration_ms": 180000,
        "warmup_min_samples": 200,
        "warmup_min_operation_duration_ms": 5000,
        "provider": "real",
    },
    "api-key-auth": {
        "contract": "COMPONENT",
        "modes": {"auth-component-c1", "auth-component-c4", "auth-component-c8"},
        "metric": "auth_duration",
        "samples": 1000,
        "duration_ms": 5000,
        "warmup": 200,
        "provider": "real",
    },
    "rag-completion": {
        "contract": "B_CLASS",
        "modes": {"e2e-stub"},
        "metric": "completion_duration",
        "samples": 30,
        "warmup": 1,
        "provider": "e2e-deterministic-stub",
        "timeout_ms": 60000,
        "max_run_duration_ms": 2400000,
    },
    "document-processing": {
        "contract": "B_CLASS",
        "modes": {"e2e-real"},
        "metric": "processing_duration",
        "samples": 20,
        "warmup": 0,
        "provider": "real",
        "timeout_ms": 120000,
        "max_run_duration_ms": 2700000,
    },
    "agent-tool-flow": {
        "contract": "B_CLASS",
        "modes": {"e2e-stub"},
        "metric": "completion_duration",
        "samples": 30,
        "warmup": 1,
        "provider": "e2e-deterministic-stub",
        "timeout_ms": 90000,
        "max_run_duration_ms": 3600000,
    },
}


def _expected_java_performance_config(scenario: str) -> dict:
    common_b = {
        "schema_version": "1.0",
        "scenario": scenario,
        "runs": 3,
        "concurrency": 1,
        "independent_runs": 3,
        "percentile_method": "nearest-rank",
    }
    if scenario == "retrieval":
        return {
            "schema_version": "1.0", "scenario": "retrieval",
            "target": "RetrievalService.search", "layer": "COMPONENT",
            "provider_mode": "real",
            "primary_concurrency": 1, "extra_concurrencies": [4, 8],
            "candidate_top_k": 50, "top_k": 10, "rrf_k": 60,
            "independent_runs": 3, "percentile_method": "nearest-rank",
            "modes": ["VECTOR", "BM25", "HYBRID", "RERANK"],
            **PERFORMANCE_CONFIG_EXTENSION,
        }
    if scenario == "api-key-auth":
        return {
            "schema_version": "1.0", "scenario": "api-key-auth",
            "mode": "auth-component",
            "auth_path": "ApiKeyAuthenticationFilter.doFilterInternal",
            "target_component": "ApiKeyAuthenticationFilter",
            "warmup_samples": 200, "min_measured_samples": 1000,
            "min_duration_ms": 5000, "max_duration_ms": 60000,
            "runs": 3, "concurrency_levels": [1, 4, 8],
            "request_timeout_ms": 30000, "provider_mode": "real",
            "percentile_method": "nearest-rank",
            "success_semantics": "HTTP 200 and SecurityContext authentication set by API Key filter",
        }
    if scenario == "rag-completion":
        return {
            **common_b, "mode": "e2e-stub", "target": "ChatOrchestrationService.chat",
            "provider_mode": "e2e-deterministic-stub", "warmup_samples": 1,
            "executions_per_run": 30, "timeout_ms": 60000,
            "max_run_duration_ms": 2400000,
            "success_semantics": "SSE stream completes and assistant message finalized to SUCCESS",
        }
    if scenario == "document-processing":
        return {
            **common_b, "mode": "e2e-real", "target": "DocumentService.uploadDocument",
            "provider_mode": "real", "warmup_samples": 0,
            "executions_per_run": 20, "timeout_ms": 120000,
            "max_run_duration_ms": 2700000,
            "success_semantics": "DocumentStatus.COMPLETED plus RetrievalTaskStatus.READY via real parser/embedding/index pipeline",
        }
    if scenario == "agent-tool-flow":
        return {
            **common_b, "mode": "e2e-stub", "target": "AgentOrchestrationService.chat",
            "provider_mode": "e2e-deterministic-stub", "warmup_samples": 1,
            "executions_per_run": 30, "timeout_ms": 90000,
            "max_run_duration_ms": 3600000,
            "success_semantics": "Agent chat completes with SUCCESS and at least one tool call was executed",
        }
    raise ValueError(f"unsupported Java scenario: {scenario}")


class RunSetAcceptanceResult:
    """Immutable validation result."""

    def __init__(self, eligible: bool, reason: str, details: dict | None = None):
        self.eligible = eligible
        self.reason = reason
        self.details = details or {}

    def to_dict(self) -> dict:
        return {
            "eligible": self.eligible,
            "reason": self.reason,
            "details": self.details,
        }


def _load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _canonical_hash(value: Any) -> str:
    payload = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _has_timestamp(value: Any) -> bool:
    return isinstance(value, str) and bool(re.fullmatch(r"\d{4}-\d{2}-\d{2}T.+", value))


def _require_probe_fact(fact: Any, label: str) -> dict:
    if not isinstance(fact, dict) or not _has_timestamp(fact.get("observed_at")) \
            or not isinstance(fact.get("source"), str) or not fact.get("source") \
            or not isinstance(fact.get("probe"), str) or not fact.get("probe"):
        raise ValueError(f"{label} timestamp/source/probe incomplete")
    return fact


def _file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _safe_link(run_set_dir: Path, name: Any) -> Path:
    if not isinstance(name, str) or not name or Path(name).name != name:
        raise ValueError(f"invalid run-set-local filename: {name!r}")
    return run_set_dir / name


def _detect_artifact_contract(manifest: dict) -> str:
    java_contract = manifest.get("artifact_contract")
    external_markers = any(
        key in manifest for key in ("measurement_status", "cleanup_status")
    )
    if java_contract in JAVA_CONTRACTS:
        if external_markers:
            return "MIXED"
        return java_contract
    if java_contract is not None:
        return "UNKNOWN"
    if external_markers:
        return "EXTERNAL_K6"
    return "UNKNOWN"


def _java_expected_metric(scenario: str, mode: str, spec: dict) -> str:
    if scenario == "retrieval":
        return "retrieval_" + mode.split("-c", 1)[0].lower()
    return str(spec["metric"])


def _mode_concurrency(mode: str) -> int:
    match = re.search(r"-c(\d+)$", mode)
    return int(match.group(1)) if match else 1


def _validate_java_percentile(
    run_set_dir: Path,
    run: dict,
    raw_path: Path,
    latencies: list[float],
    measurement_contract_evidence: dict | None = None,
) -> tuple[str | None, dict | None]:
    try:
        percentile_path = _safe_link(run_set_dir, run.get("percentile_file"))
    except ValueError as error:
        return str(error), None
    if not percentile_path.exists():
        return f"percentile artifact missing: {percentile_path.name}", None
    if _file_hash(percentile_path) != run.get("percentile_sha256"):
        return f"percentile artifact hash mismatch: {percentile_path.name}", None
    try:
        artifact = _load_json(percentile_path)
    except Exception as error:
        return f"percentile artifact unreadable: {error}", None
    if artifact.get("schema_version") != JAVA_SCHEMA_VERSION:
        return "Java percentile schema_version must be 1.1", artifact
    if artifact.get("method") != "nearest-rank":
        return "Java percentile method must be nearest-rank", artifact
    if artifact.get("input") != raw_path.name:
        return "Java percentile input linkage mismatch", artifact
    if artifact.get("sample_count") != len(latencies):
        return "Java percentile sample_count mismatch", artifact
    if artifact.get("percentiles") != compute_percentiles(latencies):
        return "Java percentile nearest-rank recompute mismatch", artifact
    contract_fields = {
        "contract_class",
        "provider_path_class",
        "measured_operations",
        "measured_operation_duration_ms",
        "measured_wall_duration_ms",
    }
    if measurement_contract_evidence is None:
        if contract_fields.intersection(artifact):
            return "Java percentile has unexpected measurement contract evidence", artifact
    else:
        for field in contract_fields:
            if artifact.get(field) != measurement_contract_evidence.get(field):
                return f"Java percentile measurement contract {field} mismatch", artifact
    return None, artifact


def _validate_java_run_set(run_set_dir: Path, manifest: dict) -> RunSetAcceptanceResult:
    scenario = str(manifest.get("scenario", ""))
    spec = JAVA_SCENARIOS.get(scenario)
    if spec is None:
        return RunSetAcceptanceResult(False, f"unsupported Java scenario: {scenario}")
    if manifest.get("schema_version") != JAVA_SCHEMA_VERSION:
        return RunSetAcceptanceResult(False, "Java schema_version must be 1.1")
    if manifest.get("evidence_class") != "bench":
        return RunSetAcceptanceResult(False, "Java evidence_class must be bench")
    if manifest.get("artifact_contract") != spec["contract"]:
        return RunSetAcceptanceResult(False, "Java artifact_contract/scenario mismatch")
    if manifest.get("evidence_purpose") != FORMAL_JAVA_PURPOSE:
        return RunSetAcceptanceResult(False, "Java smoke/non-formal evidence is ineligible")
    if manifest.get("status") != "COMPLETE":
        return RunSetAcceptanceResult(False, f"manifest status={manifest.get('status')}, expected COMPLETE")
    if manifest.get("final_acceptance_eligible") is not False:
        return RunSetAcceptanceResult(False, "writer must not self-certify acceptance")
    if manifest.get("final_acceptance_status") != "PENDING_EVIDENCE_VALIDATION":
        return RunSetAcceptanceResult(False, "Java final acceptance lifecycle marker mismatch")
    config_hash = manifest.get("config_hash")
    if not isinstance(config_hash, str) or not SHA256_RE.fullmatch(config_hash) \
            or manifest.get("config_id") != config_hash[:16]:
        return RunSetAcceptanceResult(False, "Java config hash/config_id identity mismatch")
    if manifest.get("percentile_authority") != "python-offline-nearest-rank":
        return RunSetAcceptanceResult(False, "Java percentile authority mismatch")
    if run_set_dir.name != manifest.get("run_set_id") or run_set_dir.parent.name != scenario:
        return RunSetAcceptanceResult(False, "Java run-set directory identity mismatch")

    try:
        frozen_path = _safe_link(run_set_dir, manifest.get("frozen_config_file"))
        cleanup_path = _safe_link(run_set_dir, manifest.get("cleanup_observation_file"))
        if _file_hash(frozen_path) != manifest.get("frozen_config_sha256"):
            raise ValueError("frozen config file hash mismatch")
        if _file_hash(cleanup_path) != manifest.get("cleanup_observation_sha256"):
            raise ValueError("cleanup observation hash mismatch")
        frozen = _load_json(frozen_path)
        cleanup = _load_json(cleanup_path)
        performance = frozen.get("performance_config")
        identity = frozen.get("environment_identity")
        if frozen.get("schema_version") != JAVA_SCHEMA_VERSION \
                or frozen.get("scenario") != scenario \
                or frozen.get("config_hash") != manifest.get("config_hash") \
                or frozen.get("config_id") != manifest.get("config_id") \
                or frozen.get("environment_hash") != manifest.get("environment_hash") \
                or frozen.get("canonicalization") != "sorted-compact-json-utf8-v1":
            raise ValueError("frozen config wrapper linkage mismatch")
        if not _has_timestamp(frozen.get("frozen_at")):
            raise ValueError("frozen config timestamp missing")
        application = frozen.get("application_identity")
        if application != {"name": "IntelliDesk", "module": "backend"}:
            raise ValueError("application identity mismatch")
        if not isinstance(performance, dict) or _canonical_hash(performance) != manifest.get("config_hash"):
            raise ValueError("performance config canonical hash mismatch")
        if performance != _expected_java_performance_config(scenario):
            raise ValueError("performance config does not match frozen methodology")
        if not isinstance(identity, dict) or _canonical_hash(identity) != manifest.get("environment_hash"):
            raise ValueError("environment identity canonical hash mismatch")
        if cleanup.get("schema_version") != JAVA_SCHEMA_VERSION \
                or cleanup.get("run_set_id") != manifest.get("run_set_id") \
                or cleanup.get("success") is not True:
            raise ValueError("mandatory harness cleanup did not succeed")
        if not _has_timestamp(cleanup.get("completed_at")):
            raise ValueError("cleanup observation timestamp missing")
        if scenario == "retrieval":
            preflight_path = _safe_link(run_set_dir, manifest.get("preflight_observation_file"))
            if _file_hash(preflight_path) != manifest.get("preflight_observation_sha256"):
                raise ValueError("retrieval preflight observation hash mismatch")
            validate_retrieval_preflight(manifest, _load_json(preflight_path))
    except Exception as error:
        return RunSetAcceptanceResult(False, f"Java integrity failure: {error}")

    expected_modes = manifest.get("expected_modes")
    expected_runs = manifest.get("expected_runs_per_mode")
    runs = manifest.get("runs")
    if not isinstance(expected_modes, list) or set(expected_modes) != spec["modes"]:
        return RunSetAcceptanceResult(False, "Java expected_modes do not match frozen scenario contract")
    if expected_runs != 3:
        return RunSetAcceptanceResult(False, "Java expected_runs_per_mode must be 3")
    if not isinstance(runs, list) or len(runs) != len(expected_modes) * 3:
        return RunSetAcceptanceResult(False, "Java run-link cardinality mismatch")

    seen: set[tuple[str, str]] = set()
    details: list[dict] = []
    percentiles_by_mode: dict[str, list[dict]] = {mode: [] for mode in expected_modes}
    document_ids: set[int] = set()
    for run in runs:
        if not isinstance(run, dict):
            return RunSetAcceptanceResult(False, "Java run link is not an object")
        mode = str(run.get("mode", ""))
        run_id = str(run.get("run_id", ""))
        identity_key = (mode, run_id)
        if mode not in spec["modes"] or not re.fullmatch(r"run-[1-3]", run_id) or identity_key in seen:
            return RunSetAcceptanceResult(False, f"Java invalid/duplicate run identity: {identity_key}")
        seen.add(identity_key)
        try:
            raw_path = _safe_link(run_set_dir, run.get("raw_file"))
            observation_path = _safe_link(run_set_dir, run.get("run_observation_file"))
            if _file_hash(raw_path) != run.get("raw_sha256"):
                raise ValueError("raw hash mismatch")
            if _file_hash(observation_path) != run.get("run_observation_sha256"):
                raise ValueError("observation hash mismatch")
            observation = _load_json(observation_path)
            samples = _load_raw_samples(raw_path)
        except Exception as error:
            return RunSetAcceptanceResult(False, f"{mode}/{run_id}: Java artifact failure: {error}")

        for field, expected in (("scenario", scenario), ("run_set_id", manifest.get("run_set_id")),
                                ("mode", mode), ("run_id", run_id), ("raw_file", raw_path.name)):
            if observation.get(field) != expected:
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: observation {field} mismatch")
        if observation.get("schema_version") != JAVA_SCHEMA_VERSION:
            return RunSetAcceptanceResult(False, f"{mode}/{run_id}: observation schema mismatch")
        if not _has_timestamp(observation.get("observed_at")):
            return RunSetAcceptanceResult(False, f"{mode}/{run_id}: observation timestamp missing")
        if scenario == "retrieval":
            try:
                validate_retrieval_run_observation(mode, observation)
            except ValueError as error:
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: {error}")
        elif observation.get("warmup_completed") != spec["warmup"]:
            return RunSetAcceptanceResult(False, f"{mode}/{run_id}: warmup observation mismatch")
        expected_concurrency = _mode_concurrency(mode)
        if observation.get("max_in_flight_observed") != expected_concurrency:
            return RunSetAcceptanceResult(False, f"{mode}/{run_id}: concurrency observation mismatch")
        forbidden_observation_fields = {
            "warmup_configured", "configured_concurrency", "measured_sample_count",
            "measured_duration_ms", "timeout_ms", "max_run_duration_ms",
        }
        if scenario == "retrieval":
            forbidden_observation_fields.add("warmup_completed")
            forbidden_observation_fields.add("warmup_completed_samples")
        else:
            forbidden_observation_fields.update({
                "warmup_completed_samples", "warmup_completed_operations",
                "warmup_operation_duration_ms",
                "measurement_started_after_warmup",
            })
        if forbidden_observation_fields.intersection(observation):
            return RunSetAcceptanceResult(False, f"{mode}/{run_id}: redundant observation field present")
        measured_contract_evidence = None
        if scenario == "retrieval":
            try:
                measured_contract_evidence = validate_retrieval_measured_run(mode, samples)
            except ValueError as error:
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: {error}")
        elif spec["contract"] == "COMPONENT":
            if len(samples) < spec["samples"]:
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: insufficient component sample count")
        elif len(samples) != spec["samples"]:
            return RunSetAcceptanceResult(False, f"{mode}/{run_id}: exact-N sample count mismatch")

        latencies: list[float] = []
        expected_metric = _java_expected_metric(scenario, mode, spec)
        relative_times: list[float] = []
        operation_end_times: list[float] = []
        for sample_index, sample in enumerate(samples):
            metadata = sample.get("execution_metadata")
            if not isinstance(metadata, dict):
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: execution_metadata missing")
            if metadata.get("evidence_tag") == "NOT_BENCHMARK_EVIDENCE":
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: smoke-only raw is ineligible")
            linkage = {
                "scenario": scenario,
                "run_set_id": manifest.get("run_set_id"),
                "run_id": run_id,
                "config_hash": manifest.get("config_hash"),
                "environment_hash": manifest.get("environment_hash"),
                "metric_name": expected_metric,
                "concurrency": expected_concurrency,
                "provider_mode": spec["provider"],
            }
            for field, expected in linkage.items():
                if sample.get(field) != expected:
                    return RunSetAcceptanceResult(False, f"{mode}/{run_id}: raw {field} linkage mismatch")
            if metadata.get("evidence_mode") != mode:
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: raw evidence_mode mismatch")
            if scenario == "retrieval":
                try:
                    validate_retrieval_sample_metadata(mode, metadata)
                except ValueError as error:
                    return RunSetAcceptanceResult(False, f"{mode}/{run_id}: {error}")
            if sample.get("sample_index") != sample_index:
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: measured sample index mismatch")
            latency = sample.get("latency_ms")
            if isinstance(latency, bool) or not isinstance(latency, (int, float)) \
                    or not math.isfinite(float(latency)) or float(latency) < 0:
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: latency_ms not numeric")
            if sample.get("success") is not True:
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: failed authoritative execution")
            if sample.get("error") is not None or sample.get("status_code") != 200:
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: successful raw result facts invalid")
            run_relative_time = sample.get("run_relative_time")
            if isinstance(run_relative_time, bool) or not isinstance(run_relative_time, (int, float)) \
                    or not math.isfinite(float(run_relative_time)) or run_relative_time < 0:
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: run_relative_time invalid")
            if relative_times and float(run_relative_time) < relative_times[-1]:
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: run_relative_time not monotonic")
            relative_times.append(float(run_relative_time))
            operation_end_times.append(float(run_relative_time) + float(latency))
            if spec["contract"] == "B_CLASS" \
                    and metadata.get("request_timeout_ms") != spec["timeout_ms"]:
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: raw timeout linkage mismatch")
            if scenario == "document-processing":
                document_id = metadata.get("document_id")
                if type(document_id) is not int:
                    return RunSetAcceptanceResult(False, f"{mode}/{run_id}: document_id missing")
                document_ids.add(document_id)
            if scenario == "agent-tool-flow" and (
                metadata.get("tool_call_count", 0) < 1 or metadata.get("tool_result_count", 0) < 1
            ):
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: tool-flow success facts missing")
            latencies.append(float(latency))

        if spec["contract"] == "COMPONENT" and scenario != "retrieval":
            measured_duration_ms = max(operation_end_times)
            if measured_duration_ms < spec["duration_ms"]:
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: measured duration below 5s")
            if "max_duration_ms" in spec and measured_duration_ms > spec["max_duration_ms"]:
                return RunSetAcceptanceResult(False, f"{mode}/{run_id}: measured duration exceeds 180s ceiling")

        percentile_error, percentile = _validate_java_percentile(
            run_set_dir,
            run,
            raw_path,
            latencies,
            measured_contract_evidence,
        )
        if percentile_error:
            return RunSetAcceptanceResult(False, f"{mode}/{run_id}: {percentile_error}")
        percentiles_by_mode[mode].append(percentile["percentiles"])
        detail = {"mode": mode, "run_id": run_id, "samples": len(samples),
                  "percentiles": percentile["percentiles"]}
        if measured_contract_evidence is not None:
            detail["measurement_contract"] = measured_contract_evidence
        details.append(detail)

    if len(seen) != len(spec["modes"]) * 3:
        return RunSetAcceptanceResult(False, "Java incomplete mode/run matrix")

    if spec["contract"] == "COMPONENT":
        for mode, summaries in percentiles_by_mode.items():
            p50_values = [item["p50"] for item in summaries]
            p95_values = [item["p95"] for item in summaries]
            mean_p50 = statistics.mean(p50_values)
            cv = 0.0 if mean_p50 == 0 and len(set(p50_values)) == 1 else (
                float("inf") if mean_p50 == 0 else statistics.pstdev(p50_values) / mean_p50
            )
            median_p95 = statistics.median(p95_values)
            max_p95_deviation = 0.0 if median_p95 == 0 and len(set(p95_values)) == 1 else (
                float("inf") if median_p95 == 0 else max(abs(value - median_p95) / median_p95 for value in p95_values)
            )
            if cv > 0.10:
                return RunSetAcceptanceResult(False, f"{mode}: component CV(p50) exceeds 10%")
            if max_p95_deviation > 0.15:
                return RunSetAcceptanceResult(False, f"{mode}: component p95 deviation exceeds 15%")

    if scenario == "document-processing":
        try:
            provenance_path = _safe_link(run_set_dir, manifest.get("runtime_provenance_file"))
            if _file_hash(provenance_path) != manifest.get("runtime_provenance_sha256"):
                raise ValueError("runtime provenance hash mismatch")
            provenance = _load_json(provenance_path)
            if provenance.get("schema_version") != JAVA_SCHEMA_VERSION \
                    or provenance.get("scenario") != scenario \
                    or provenance.get("run_set_id") != manifest.get("run_set_id") \
                    or not _has_timestamp(provenance.get("captured_at")):
                raise ValueError("runtime provenance identity/timestamp mismatch")
            if set(provenance.get("measured_document_ids", [])) != document_ids:
                raise ValueError("runtime provenance measured document linkage mismatch")
            facts = provenance.get("documents")
            if not isinstance(facts, list) or {fact.get("document_id") for fact in facts} != document_ids:
                raise ValueError("runtime provenance document facts incomplete")
            for fact in facts:
                _require_probe_fact(fact, "document fact")
                if fact.get("document_status") != "COMPLETED" or fact.get("retrieval_task_status") != "READY":
                    raise ValueError("document/index terminal state is not COMPLETED/READY")
                if not fact.get("document_completed_at") or fact.get("document_completed_at") == "null" \
                        or type(fact.get("document_index_task_id")) is not int \
                        or not fact.get("document_index_message_id") \
                        or fact.get("document_index_task_status") != "SUCCEEDED" \
                        or type(fact.get("document_index_attempt_count")) is not int \
                        or fact.get("document_index_attempt_count") < 1:
                    raise ValueError("document task terminal facts incomplete")
                if not fact.get("parser_format") or not fact.get("parser_class") \
                        or not SHA256_RE.fullmatch(str(fact.get("parser_metadata_sha256", ""))) \
                        or type(fact.get("chunk_count")) is not int or fact.get("chunk_count") < 1 \
                        or not SHA256_RE.fullmatch(str(fact.get("chunk_digest", ""))):
                    raise ValueError("document parser/chunk/index facts incomplete")
                if type(fact.get("retrieval_task_id")) is not int \
                        or type(fact.get("retrieval_generation")) is not int \
                        or fact.get("retrieval_generation") < 1 \
                        or not fact.get("embedding_model") \
                        or type(fact.get("embedding_dimension")) is not int \
                        or fact.get("embedding_dimension") < 1 \
                        or not fact.get("elasticsearch_index") \
                        or type(fact.get("indexed_chunk_count")) is not int \
                        or fact.get("indexed_chunk_count") < 1:
                    raise ValueError("retrieval/index factual chain incomplete")
                if not fact.get("minio_bucket") \
                        or not SHA256_RE.fullmatch(str(fact.get("minio_object_key_sha256", ""))) \
                        or not isinstance(fact.get("minio_object_size"), int) \
                        or fact.get("minio_object_size") < 1 or not fact.get("minio_object_etag"):
                    raise ValueError("MinIO object stat facts incomplete")
            pre = provenance.get("pre_measurement")
            final = provenance.get("final_snapshot")
            for phase, snapshot in (("pre-measurement", pre), ("pre-finalization", final)):
                if not isinstance(snapshot, dict) or snapshot.get("capture_phase") != phase \
                        or not _has_timestamp(snapshot.get("observed_at")) \
                        or not snapshot.get("source"):
                    raise ValueError(f"live {phase} snapshot incomplete")
                jdbc = _require_probe_fact(snapshot.get("jdbc"), f"{phase} JDBC")
                minio = _require_probe_fact(snapshot.get("minio"), f"{phase} MinIO")
                rabbit = _require_probe_fact(snapshot.get("rabbitmq"), f"{phase} RabbitMQ")
                if jdbc.get("valid") is not True or jdbc.get("select_one") != 1 \
                        or not all(jdbc.get(key) for key in
                                   ("database_product", "database_version", "driver_name", "driver_version")):
                    raise ValueError(f"live {phase} JDBC probe incomplete")
                if minio.get("bucket_exists") is not True \
                        or not minio.get("endpoint") or not minio.get("bucket"):
                    raise ValueError(f"live {phase} MinIO probe incomplete")
                if rabbit.get("open") is not True \
                        or not all(rabbit.get(key) for key in ("server_product", "server_version", "server_platform")) \
                        or type(rabbit.get("listener_containers")) is not int \
                        or type(rabbit.get("listeners_running")) is not int \
                        or rabbit.get("listeners_running") < 1:
                    raise ValueError(f"live {phase} RabbitMQ probe incomplete")
                for queue_key in ("document_queue", "retrieval_queue"):
                    queue = _require_probe_fact(rabbit.get(queue_key), f"{phase} RabbitMQ {queue_key}")
                    if not queue.get("name") or type(queue.get("message_count")) is not int \
                            or type(queue.get("consumer_count")) is not int \
                            or queue.get("consumer_count") < 1:
                        raise ValueError(f"live {phase} RabbitMQ {queue_key} facts incomplete")
        except Exception as error:
            return RunSetAcceptanceResult(False, f"document runtime provenance failure: {error}")

    return RunSetAcceptanceResult(
        True,
        "run-set eligible for final acceptance",
        {"artifact_contract": manifest["artifact_contract"], "runs": details},
    )


def _field(manifest: dict, key: str) -> Any:
    value = manifest.get(key)
    if value is None or (isinstance(value, str) and not value.strip()):
        raise ValueError(f"manifest missing required field: {key}")
    return value


def _find_raw_files(run_set_dir: Path, scenario: str, mode: str) -> list[Path]:
    prefix = f"raw-{scenario}-{mode}-"
    return sorted([
        p for p in run_set_dir.iterdir()
        if p.is_file() and p.name.startswith(prefix) and p.name.endswith(".jsonl")
    ])


def _load_raw_samples(raw_path: Path) -> list[dict]:
    samples = []
    with open(raw_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            samples.append(json.loads(line))
    return samples


def _scan_external_raw(
    raw_path: Path,
    scenario: str,
    run_set_id: str,
    run_id: str,
    config_hash: str,
    vu_level: int,
) -> tuple[str | None, dict]:
    """Validate a potentially multi-GB External raw file in one streaming pass."""
    result = {
        "raw_count": 0,
        "authoritative_count": 0,
        "sustain_count": 0,
        "sustain_latencies": [],
        "sustain_error_count": 0,
        "endpoints": set(),
        "ttft_detected": scenario == "rag-pipeline",
        "missing_ttft": [],
    }
    with raw_path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            sample = json.loads(line)
            if not isinstance(sample, dict):
                return f"raw line {line_number} is not an object", result
            result["raw_count"] += 1
            if not _is_authoritative_latency_record(sample):
                continue
            authoritative_index = result["authoritative_count"]
            result["authoritative_count"] += 1
            phase = sample.get("phase")
            if phase not in VALID_EXTERNAL_PHASES:
                return f"authoritative latency sample {authoritative_index} has invalid phase={phase!r}", result
            if scenario == "http-concurrency":
                for field in (
                    "latency_measurement_source",
                    "latency_request_boundary",
                    "latency_pre_percentile_quantization",
                ):
                    expected = HTTP_CONCURRENCY_LATENCY_CONTRACT[field]
                    if sample.get(field) != expected:
                        return f"HTTP Concurrency raw {field} mismatch", result
            for field, expected in (
                ("scenario", scenario), ("run_set_id", run_set_id), ("run_id", run_id),
                ("config_hash", config_hash),
            ):
                if sample.get(field) != expected:
                    return f"raw {field} linkage mismatch", result
            latency = sample.get("latency_ms")
            if isinstance(latency, bool) or not isinstance(latency, (int, float)) \
                    or not math.isfinite(float(latency)) or float(latency) < 0:
                return "authoritative latency sample missing valid nonnegative latency_ms", result
            if sample.get("vu") != vu_level or sample.get("concurrency") != vu_level:
                return "raw VU/concurrency linkage mismatch", result
            if phase != "sustain":
                continue

            sustain_index = result["sustain_count"]
            result["sustain_count"] += 1
            result["sustain_latencies"].append(float(latency))
            if not sample.get("success"):
                result["sustain_error_count"] += 1
            endpoint = sample.get("endpoint")
            if endpoint:
                result["endpoints"].add(endpoint)
            if sample.get("ttft_mode") or sample.get("qualifying_event_type") is not None:
                result["ttft_detected"] = True
            if scenario == "rag-pipeline" and sample.get("success"):
                t0 = sample.get("t0_monotonic")
                t1 = sample.get("t1_monotonic")
                ttft = sample.get("ttft_ms")
                valid_ttft = all(
                    not isinstance(value, bool)
                    and isinstance(value, (int, float))
                    and math.isfinite(float(value))
                    for value in (t0, t1, ttft)
                )
                if (
                    not valid_ttft
                    or float(ttft) < 0
                    or float(t1) < float(t0)
                    or not math.isclose(float(latency), float(ttft), rel_tol=1e-9, abs_tol=1e-6)
                ):
                    if len(result["missing_ttft"]) < 10:
                        result["missing_ttft"].append(sustain_index)
    return None, result


def _is_authoritative_latency_record(sample: dict) -> bool:
    if sample.get("metric_name") == AUTHORITATIVE_LATENCY_METRIC:
        return True
    execution_metadata = sample.get("execution_metadata")
    return (
        isinstance(execution_metadata, dict)
        and execution_metadata.get("k6_metric") == AUTHORITATIVE_LATENCY_METRIC
    )


def _percentile_path_for_raw(raw_path: Path) -> Path:
    return raw_path.with_name(
        raw_path.name.replace("raw-", "percentiles-", 1).replace(".jsonl", ".json")
    )


def _artifact_input_matches_raw(input_value: Any, raw_path: Path) -> bool:
    if not isinstance(input_value, str) or not input_value.strip():
        return False
    input_path = Path(input_value.replace("\\", "/"))
    expected = raw_path.resolve()
    if input_path.is_absolute():
        return input_path.resolve() == expected
    candidates = {(Path.cwd() / input_path).resolve()}
    if len(input_path.parts) == 1:
        candidates.add((raw_path.parent / input_path).resolve())
    return expected in candidates


def _validate_percentile_artifact(
    raw_path: Path,
    sustain_latencies: list[float],
) -> tuple[str | None, dict | None, list[float]]:
    percentile_path = _percentile_path_for_raw(raw_path)
    if not percentile_path.exists():
        return f"percentile artifact missing: {percentile_path.name}", None, []
    try:
        artifact = _load_json(percentile_path)
    except Exception as e:
        return f"percentile artifact unreadable: {percentile_path.name}: {e}", None, []

    if artifact.get("method") != "nearest-rank":
        return (
            f"percentile artifact {percentile_path.name}: method must be nearest-rank",
            artifact,
            [],
        )
    if not _artifact_input_matches_raw(artifact.get("input"), raw_path):
        return (
            f"percentile artifact {percentile_path.name}: input does not match {raw_path.name}",
            artifact,
            [],
        )

    if not sustain_latencies:
        return f"raw {raw_path.name}: zero valid sustain latency samples", artifact, []

    sample_count = artifact.get("sample_count")
    if type(sample_count) is not int or sample_count != len(sustain_latencies):
        return (
            f"percentile artifact {percentile_path.name}: sample_count={sample_count}, "
            f"expected {len(sustain_latencies)} sustain samples",
            artifact,
            sustain_latencies,
        )

    expected_percentiles = compute_percentiles(sustain_latencies)
    if artifact.get("percentiles") != expected_percentiles:
        return (
            f"percentile artifact {percentile_path.name}: percentiles do not match "
            "sustain-only raw nearest-rank recompute",
            artifact,
            sustain_latencies,
        )
    return None, artifact, sustain_latencies


def _extract_vu_level(run_id: str) -> int | None:
    match = re.match(r"^vu(\d+)-run-(\d+)$", run_id)
    if match:
        return int(match.group(1))
    return None


def _extract_independent_index(run_id: str) -> int | None:
    match = re.match(r"^vu(\d+)-run-(\d+)$", run_id)
    if match:
        return int(match.group(2))
    return None


def _expected_path_pattern(target_template: str) -> re.Pattern:
    """Convert a logical target template into a path-matching regex.

    Placeholders like {workspace_id} match any non-empty path segment.
    The pattern is anchored to the end of the path so that suffix-only
    checks are robust.
    """
    escaped = re.escape(target_template)
    # re.escape preserves braces; replace escaped placeholders with segment matcher.
    pattern = escaped.replace(r"\{", "{").replace(r"\}", "}")
    pattern = re.sub(r"\{[^}]+\}", r"[^/]+", pattern)
    return re.compile(pattern + r"$")


def _endpoint_path(endpoint: str) -> str:
    """Return the URL path component of an endpoint string."""
    if not endpoint:
        return ""
    # Strip scheme and host.
    if "://" in endpoint:
        endpoint = endpoint.split("://", 1)[1]
    # Strip host:port.
    if "/" in endpoint:
        return "/" + endpoint.split("/", 1)[1]
    return "/"


def validate_run_set_acceptance(
    run_set_dir: Path,
    bench_root: Path | None = None,
) -> RunSetAcceptanceResult:
    """Validate whether a run-set is eligible for final benchmark acceptance.

    Args:
        run_set_dir: path to the run-set directory.
        bench_root: optional project benchmark root (e.g. docs/evaluation/bench)
                    used to resolve the canonical frozen config.

    Returns:
        RunSetAcceptanceResult with eligible=True only if all gates pass.
    """
    run_set_dir = Path(run_set_dir)
    manifest_path = run_set_dir / "run_set_manifest.json"
    if not manifest_path.exists():
        return RunSetAcceptanceResult(False, "run-set manifest missing")

    try:
        manifest = _load_json(manifest_path)
    except Exception as e:
        return RunSetAcceptanceResult(False, f"manifest unreadable: {e}")

    artifact_contract = _detect_artifact_contract(manifest)
    if artifact_contract in JAVA_CONTRACTS:
        return _validate_java_run_set(run_set_dir, manifest)
    if artifact_contract == "MIXED":
        return RunSetAcceptanceResult(False, "mixed External/Java artifact contract markers")
    if artifact_contract != "EXTERNAL_K6":
        return RunSetAcceptanceResult(False, "unknown or insufficient artifact contract")

    # Required manifest fields.
    try:
        scenario = str(_field(manifest, "scenario"))
        config_hash = str(_field(manifest, "config_hash"))
        run_set_id = str(_field(manifest, "run_set_id"))
        expected_modes = _field(manifest, "expected_modes")
        expected_runs_per_mode = int(_field(manifest, "expected_runs_per_mode"))
        manifest_status = str(manifest.get("status", ""))
        cleanup_status = str(manifest.get("cleanup_status", ""))
        measurement_status = str(manifest.get("measurement_status", ""))
    except ValueError as e:
        return RunSetAcceptanceResult(False, str(e))

    if not isinstance(expected_modes, list) or not expected_modes:
        return RunSetAcceptanceResult(False, "expected_modes missing or empty")
    if expected_runs_per_mode < 1:
        return RunSetAcceptanceResult(False, "expected_runs_per_mode must be >= 1")
    required_modes = REQUIRED_EXTERNAL_MODES.get(scenario)
    if required_modes is None:
        return RunSetAcceptanceResult(False, f"unsupported External k6 scenario: {scenario}")
    if set(expected_modes) != required_modes:
        return RunSetAcceptanceResult(
            False,
            f"external expected_modes={sorted(expected_modes)}, required {sorted(required_modes)}",
        )

    if manifest_status != "COMPLETE":
        return RunSetAcceptanceResult(
            False,
            f"manifest status={manifest_status}, expected COMPLETE",
        )

    # Directory identity checks.
    if run_set_dir.name != run_set_id:
        return RunSetAcceptanceResult(
            False,
            f"run-set id mismatch: directory={run_set_dir.name}, manifest={run_set_id}",
        )
    if run_set_dir.parent.name != scenario:
        return RunSetAcceptanceResult(
            False,
            f"scenario mismatch: directory={run_set_dir.parent.name}, manifest={scenario}",
        )

    # Cleanup and measurement lifecycle.
    if measurement_status != "COMPLETE":
        return RunSetAcceptanceResult(
            False,
            f"measurement_status={measurement_status}, expected COMPLETE",
        )
    if cleanup_status != "COMPLETE":
        return RunSetAcceptanceResult(
            False,
            f"cleanup_status={cleanup_status}, expected COMPLETE",
        )

    # Config hash verification against canonical frozen config.
    target_template = None
    frozen_config_path = run_set_dir / "frozen_config.json"
    if scenario in EXTERNAL_K6_SCENARIOS and not frozen_config_path.exists():
        return RunSetAcceptanceResult(False, "external run-set frozen_config.json missing")
    if frozen_config_path.exists():
        try:
            frozen_config = _load_json(frozen_config_path)
            frozen_scenario = frozen_config.get("scenario")
            if (
                scenario in EXTERNAL_K6_SCENARIOS
                and frozen_scenario is not None
                and frozen_scenario != scenario
            ):
                return RunSetAcceptanceResult(
                    False,
                    f"frozen config scenario mismatch: manifest={scenario}, frozen_config={frozen_scenario}",
                )
            frozen_hash = frozen_config.get("config_hash")
            if frozen_hash != config_hash:
                return RunSetAcceptanceResult(
                    False,
                    f"config_hash mismatch: manifest={config_hash}, frozen_config={frozen_hash}",
                )
            perf = frozen_config.get("performance_config", {})
            target_template = perf.get("target")
            if scenario in EXTERNAL_K6_SCENARIOS:
                if perf.get("iteration_rate_policy") != "closed-model-ramping-vus":
                    return RunSetAcceptanceResult(
                        False, "external frozen config iteration policy must match closed-model ramping-vus execution"
                    )
                expected_latency_semantics = (
                    "first_non_empty_token_ttft_ms"
                    if scenario == "rag-pipeline"
                    else "request_duration_ms"
                )
                if perf.get("authoritative_latency_semantics") != expected_latency_semantics:
                    return RunSetAcceptanceResult(
                        False, "external frozen config authoritative latency semantics mismatch"
                    )
                if scenario == "http-concurrency":
                    for field, expected in HTTP_CONCURRENCY_LATENCY_CONTRACT.items():
                        if perf.get(field) != expected:
                            return RunSetAcceptanceResult(
                                False,
                                f"HTTP Concurrency frozen config {field} mismatch",
                            )
                    harness_identity = frozen_config.get("benchmark_harness_identity")
                    if not isinstance(harness_identity, dict):
                        return RunSetAcceptanceResult(
                            False, "HTTP Concurrency benchmark_harness_identity missing"
                        )
                    expected_components = {
                        "k6_script_template_sha256",
                        "k6_runner_sha256",
                        "k6_collector_sha256",
                        "percentile_sha256",
                        "orchestrator_sha256",
                        "validator_sha256",
                    }
                    components = harness_identity.get("components")
                    if not isinstance(components, dict) or set(components) != expected_components:
                        return RunSetAcceptanceResult(
                            False, "HTTP Concurrency harness component hash set mismatch"
                        )
                    if any(not isinstance(value, str) or not SHA256_RE.fullmatch(value)
                           for value in components.values()):
                        return RunSetAcceptanceResult(
                            False, "HTTP Concurrency harness component hash invalid"
                        )
                    identity_payload = {
                        key: value
                        for key, value in harness_identity.items()
                        if key != "behavior_hash"
                    }
                    recomputed_behavior_hash = hashlib.sha256(
                        json.dumps(
                            identity_payload,
                            sort_keys=True,
                            separators=(",", ":"),
                            ensure_ascii=False,
                        ).encode("utf-8")
                    ).hexdigest()
                    if harness_identity.get("behavior_hash") != recomputed_behavior_hash:
                        return RunSetAcceptanceResult(
                            False, "HTTP Concurrency behavior_hash mismatch"
                        )
                expected_rate_policy = (
                    "disabled_for_controlled_performance_measurement"
                    if scenario in {"rag-pipeline", "api-key-auth"}
                    else "not_applicable_public_health_path"
                )
                if perf.get("application_rate_limit_policy") != expected_rate_policy:
                    return RunSetAcceptanceResult(
                        False, "external frozen config application rate-limit policy mismatch"
                    )
                if scenario in {"rag-pipeline", "api-key-auth"} and (
                    frozen_config.get("environment_identity", {}).get("backend_rate_limit_enabled")
                    != "false"
                ):
                    return RunSetAcceptanceResult(
                        False, "authenticated external frozen environment must record backend rate limiting disabled"
                    )
                if scenario == "rag-pipeline":
                    if perf.get("provider_boundary") != (
                        "deterministic_e2e_stub_at_provider_boundary_real_system_pipeline"
                    ):
                        return RunSetAcceptanceResult(
                            False, "RAG external frozen config provider boundary mismatch"
                        )
                    if perf.get("conversation_isolation") != "one_conversation_per_k6_vu":
                        return RunSetAcceptanceResult(
                            False, "RAG external frozen config conversation isolation mismatch"
                        )
                    if perf.get("post_ttft_settle_delay_ms") != 200:
                        return RunSetAcceptanceResult(
                            False, "RAG external frozen config post-TTFT settle delay mismatch"
                        )
                    if perf.get("helper_request_queue_size") != 128:
                        return RunSetAcceptanceResult(
                            False, "RAG external frozen config helper request queue size mismatch"
                        )
                    provider_identity = frozen_config.get("environment_identity", {}).get(
                        "backend_provider_identity", {}
                    )
                    required_provider_identity = {
                        "chat_base_url": "http://provider-stub:18080",
                        "chat_model": "test-model",
                        "embedding_base_url": "http://provider-stub:18080",
                        "embedding_model": "test-model",
                        "rerank_enabled": "false",
                    }
                    if any(
                        provider_identity.get(key) != value
                        for key, value in required_provider_identity.items()
                    ):
                        return RunSetAcceptanceResult(
                            False, "RAG external frozen environment does not record the approved deterministic provider boundary"
                        )
                if "sustain stage" not in str(perf.get("sample_collection_semantics", "")):
                    return RunSetAcceptanceResult(
                        False, "external frozen config must declare sustain-stage authoritative samples"
                    )
            if scenario in EXTERNAL_K6_SCENARIOS and perf.get("percentile_method") not in (
                None,
                "nearest-rank",
            ):
                return RunSetAcceptanceResult(
                    False,
                    "external frozen config percentile_method must be nearest-rank",
                )
        except Exception as e:
            return RunSetAcceptanceResult(
                False,
                f"frozen_config.json unreadable: {e}",
            )

    if scenario == "http-concurrency":
        preflight_name = manifest.get("launcher_preflight_file")
        if preflight_name != "launcher_preflight.json":
            return RunSetAcceptanceResult(False, "HTTP Concurrency launcher preflight linkage missing")
        preflight_path = run_set_dir / preflight_name
        if not preflight_path.is_file():
            return RunSetAcceptanceResult(False, "HTTP Concurrency launcher_preflight.json missing")
        actual_preflight_hash = hashlib.sha256(preflight_path.read_bytes()).hexdigest()
        if manifest.get("launcher_preflight_sha256") != actual_preflight_hash:
            return RunSetAcceptanceResult(False, "HTTP Concurrency launcher preflight hash mismatch")
        try:
            preflight = _load_json(preflight_path)
        except Exception as e:
            return RunSetAcceptanceResult(False, f"HTTP Concurrency launcher preflight unreadable: {e}")
        if preflight.get("status") != "PASS":
            return RunSetAcceptanceResult(False, "HTTP Concurrency launcher preflight did not pass")
        if preflight.get("resolution_source") not in {"K6_PATH", "PATH"}:
            return RunSetAcceptanceResult(False, "HTTP Concurrency launcher resolution source invalid")
        if preflight.get("observed_version") != frozen_config.get("k6_version"):
            return RunSetAcceptanceResult(False, "HTTP Concurrency preflight/frozen k6 version mismatch")
        if preflight.get("expected_version") != frozen_config.get("k6_version"):
            return RunSetAcceptanceResult(False, "HTTP Concurrency expected k6 version linkage mismatch")
        binary_hash = preflight.get("binary_sha256")
        if not isinstance(binary_hash, str) or not SHA256_RE.fullmatch(binary_hash):
            return RunSetAcceptanceResult(False, "HTTP Concurrency k6 binary hash invalid")
        for manifest_field, preflight_field in (
            ("k6_resolved_path", "resolved_path"),
            ("k6_version", "observed_version"),
            ("k6_binary_sha256", "binary_sha256"),
        ):
            if manifest.get(manifest_field) != preflight.get(preflight_field):
                return RunSetAcceptanceResult(
                    False, f"HTTP Concurrency manifest {manifest_field} linkage mismatch"
                )

    manifest_run_records: dict[tuple[str, str], dict] = {}
    if scenario in EXTERNAL_K6_SCENARIOS:
        runs = manifest.get("runs")
        expected_manifest_runs = len(expected_modes) * expected_runs_per_mode
        if not isinstance(runs, list) or len(runs) != expected_manifest_runs:
            actual = len(runs) if isinstance(runs, list) else 0
            return RunSetAcceptanceResult(
                False,
                f"external manifest has {actual} run records, expected {expected_manifest_runs}",
            )
        for run_record in runs:
            if not isinstance(run_record, dict):
                return RunSetAcceptanceResult(False, "external manifest run record is not an object")
            key = (str(run_record.get("mode", "")), str(run_record.get("run_id", "")))
            if not all(key) or key in manifest_run_records:
                return RunSetAcceptanceResult(
                    False,
                    f"external manifest invalid or duplicate run identity: mode={key[0]}, run_id={key[1]}",
                )
            manifest_run_records[key] = run_record

    # Validate each expected mode.
    all_mode_details = []
    overall_error_count = 0
    overall_sample_count = 0

    for mode in expected_modes:
        raw_files = _find_raw_files(run_set_dir, scenario, mode)
        if len(raw_files) != expected_runs_per_mode:
            return RunSetAcceptanceResult(
                False,
                f"mode {mode}: {len(raw_files)} raw files, expected {expected_runs_per_mode}",
                {"mode": mode, "raw_files": [p.name for p in raw_files]},
            )

        mode_samples_total = 0
        mode_raw_samples_total = 0
        mode_error_count = 0
        vu_level_runs: dict[int, set[int]] = {}
        run_details = []

        for raw_path in raw_files:
            run_name = raw_path.stem
            # Derive run_id from filename: raw-<scenario>-<mode>-<run_id>.jsonl
            prefix = f"raw-{scenario}-{mode}-"
            run_id = run_name[len(prefix):]

            vu_level = _extract_vu_level(run_id)
            run_index = _extract_independent_index(run_id)
            if vu_level is not None and run_index is not None:
                vu_level_runs.setdefault(vu_level, set()).add(run_index)

            manifest_run = None
            if scenario in EXTERNAL_K6_SCENARIOS:
                if vu_level is None or run_index is None:
                    return RunSetAcceptanceResult(
                        False,
                        f"mode {mode} run {run_id}: invalid external run identity",
                    )
                manifest_run = manifest_run_records.get((mode, run_id))
                if manifest_run is None:
                    return RunSetAcceptanceResult(
                        False,
                        f"mode {mode} run {run_id}: manifest run record missing",
                    )
                if manifest_run.get("raw_file") != raw_path.name:
                    return RunSetAcceptanceResult(
                        False,
                        f"mode {mode} run {run_id}: manifest raw_file linkage mismatch",
                    )
                if manifest_run.get("vu_level") != vu_level:
                    return RunSetAcceptanceResult(
                        False,
                        f"mode {mode} run {run_id}: manifest VU linkage mismatch",
                    )
                if manifest_run.get("independent_run_index") != run_index:
                    return RunSetAcceptanceResult(
                        False,
                        f"mode {mode} run {run_id}: manifest independent-run linkage mismatch",
                    )

            try:
                scan_error, scan = _scan_external_raw(
                    raw_path, scenario, run_set_id, run_id, config_hash, vu_level
                )
            except Exception as e:
                return RunSetAcceptanceResult(
                    False, f"mode {mode} run {run_id}: raw file unreadable: {e}"
                )
            if scan_error:
                return RunSetAcceptanceResult(False, f"mode {mode} run {run_id}: {scan_error}")
            if scan["raw_count"] == 0:
                return RunSetAcceptanceResult(False, f"mode {mode} run {run_id}: no samples")
            if scan["authoritative_count"] == 0:
                return RunSetAcceptanceResult(
                    False, f"mode {mode} run {run_id}: no authoritative latency measurement records"
                )
            if scan["sustain_count"] == 0:
                return RunSetAcceptanceResult(
                    False, f"mode {mode} run {run_id}: zero valid sustain latency samples"
                )

            mode_raw_samples_total += scan["raw_count"]
            percentile_error, percentile_artifact, sustain_latencies = _validate_percentile_artifact(
                raw_path, scan["sustain_latencies"]
            )
            if percentile_error:
                return RunSetAcceptanceResult(
                    False, f"mode {mode} run {run_id}: {percentile_error}"
                )
            if manifest_run.get("percentiles") != percentile_artifact:
                return RunSetAcceptanceResult(
                    False, f"mode {mode} run {run_id}: manifest percentile linkage mismatch"
                )
            run_details.append({
                "run_id": run_id,
                "vu_level": vu_level,
                "independent_run_index": run_index,
                "sustain_samples": len(sustain_latencies),
                "percentile_file": _percentile_path_for_raw(raw_path).name,
                "percentiles": percentile_artifact["percentiles"],
            })
            mode_samples_total += scan["sustain_count"]
            mode_error_count += scan["sustain_error_count"]

            # Target / endpoint validation uses authoritative sustain samples.
            endpoints = scan["endpoints"]
            if not endpoints:
                return RunSetAcceptanceResult(
                    False,
                    f"mode {mode} run {run_id}: missing endpoint in samples",
                )
            if target_template:
                pattern = _expected_path_pattern(target_template)
                for endpoint in endpoints:
                    path = _endpoint_path(endpoint)
                    if not pattern.search(path):
                        return RunSetAcceptanceResult(
                            False,
                            f"mode {mode} run {run_id}: endpoint {endpoint} does not match target template {target_template}",
                        )
            if scenario == "rag-pipeline" and vu_level is not None and len(endpoints) != vu_level:
                return RunSetAcceptanceResult(
                    False,
                    f"mode {mode} run {run_id}: observed {len(endpoints)} isolated conversation endpoints, expected {vu_level}",
                )

            # TTFT-specific validation.
            if scan["ttft_detected"] and scan["missing_ttft"]:
                return RunSetAcceptanceResult(
                    False,
                    f"mode {mode} run {run_id}: successful samples missing TTFT fields",
                    {"missing_indices": scan["missing_ttft"]},
                )

        # After processing all raw files for the mode.
        # VU-level / independent-run validation for external k6 scenarios.
        if scenario in EXTERNAL_VU_SCENARIOS:
            missing_levels = MANDATORY_VU_LEVELS - set(vu_level_runs.keys())
            if missing_levels:
                return RunSetAcceptanceResult(
                    False,
                    f"mode {mode}: missing mandatory VU levels {sorted(missing_levels)}",
                    {"mode": mode, "vu_levels": sorted(vu_level_runs.keys())},
                )
            for level, indices in sorted(vu_level_runs.items()):
                if len(indices) < MIN_RUNS_PER_VU_LEVEL:
                    return RunSetAcceptanceResult(
                        False,
                        f"mode {mode}: VU {level} has {len(indices)} independent runs, "
                        f"expected >= {MIN_RUNS_PER_VU_LEVEL}",
                        {"mode": mode, "vu_level": level, "run_indices": sorted(indices)},
                    )
            for level in sorted(MANDATORY_VU_LEVELS):
                p95_values = [
                    item["percentiles"]["p95"]
                    for item in run_details
                    if item["vu_level"] == level
                ]
                median_p95 = statistics.median(p95_values)
                deviation = (
                    0.0
                    if median_p95 == 0 and len(set(p95_values)) == 1
                    else float("inf")
                    if median_p95 == 0
                    else max(abs(value - median_p95) / median_p95 for value in p95_values)
                )
                if deviation > 0.20:
                    return RunSetAcceptanceResult(
                        False,
                        f"mode {mode}: VU {level} external p95 deviation exceeds 20%",
                    )
        elif vu_level_runs:
            # Non-external scenarios that happen to encode VU levels still need
            # at least MIN_RUNS_PER_VU_LEVEL independent runs per used level.
            for level, indices in sorted(vu_level_runs.items()):
                if len(indices) < MIN_RUNS_PER_VU_LEVEL:
                    return RunSetAcceptanceResult(
                        False,
                        f"mode {mode}: VU {level} has {len(indices)} independent runs, "
                        f"expected >= {MIN_RUNS_PER_VU_LEVEL}",
                        {"mode": mode, "vu_level": level, "run_indices": sorted(indices)},
                    )

        overall_sample_count += mode_samples_total
        overall_error_count += mode_error_count

        all_mode_details.append({
            "mode": mode,
            "raw_files": len(raw_files),
            "raw_samples": mode_raw_samples_total,
            "authoritative_samples": mode_samples_total,
            "errors": mode_error_count,
            "vu_levels": sorted(vu_level_runs.keys()),
            "runs": run_details,
        })

    # Global error-rate gate: any non-zero error rate fails final acceptance.
    if overall_sample_count > 0 and overall_error_count > 0:
        error_rate = overall_error_count / overall_sample_count
        return RunSetAcceptanceResult(
            False,
            f"error rate {error_rate:.6f} ({overall_error_count}/{overall_sample_count}) > 0",
            {"mode_details": all_mode_details},
        )

    return RunSetAcceptanceResult(
        True,
        "run-set eligible for final acceptance",
        {"mode_details": all_mode_details, "total_samples": overall_sample_count},
    )


def main(argv: list[str]) -> int:
    import argparse
    parser = argparse.ArgumentParser(description="Validate benchmark run-set acceptance eligibility")
    parser.add_argument("run_set_dir", help="Path to run-set directory")
    parser.add_argument("--bench-root", default=None, help="Benchmark root directory")
    args = parser.parse_args(argv)

    result = validate_run_set_acceptance(Path(args.run_set_dir), Path(args.bench_root) if args.bench_root else None)
    print(json.dumps(result.to_dict(), ensure_ascii=False, indent=2))
    return 0 if result.eligible else 1


if __name__ == "__main__":
    import sys
    sys.exit(main(sys.argv[1:]))
