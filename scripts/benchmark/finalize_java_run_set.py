#!/usr/bin/env python3
"""Finalize future Java Component/B-class benchmark evidence.

The Java harness owns execution, raw, observations, runtime provenance, and
cleanup. This tool is the sole authoritative nearest-rank percentile writer and
the sole writer allowed to transition a schema-1.1 Java run-set from PARTIAL to
COMPLETE. A failed finalization is retained as FAILED; retry requires a new
run-set ID.
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
from datetime import datetime, timezone
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


JAVA_SCHEMA_VERSION = "1.1"
VALID_CONTRACTS = {"COMPONENT", "B_CLASS"}
FORMAL_PURPOSE = "FORMAL_BENCHMARK_CANDIDATE"
SCENARIO_CONTRACTS = {
    "retrieval": ("COMPONENT", {f"{mode}-c{concurrency}" for mode in ("VECTOR", "BM25", "HYBRID", "RERANK") for concurrency in (1, 4, 8)}),
    "api-key-auth": ("COMPONENT", {"auth-component-c1", "auth-component-c4", "auth-component-c8"}),
    "rag-completion": ("B_CLASS", {"e2e-stub"}),
    "document-processing": ("B_CLASS", {"e2e-real"}),
    "agent-tool-flow": ("B_CLASS", {"e2e-stub"}),
}
METRICS = {
    "retrieval": None,  # validated from mode below
    "api-key-auth": "auth_duration",
    "rag-completion": "completion_duration",
    "document-processing": "processing_duration",
    "agent-tool-flow": "completion_duration",
}


def _load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def _canonical_hash(value: Any) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _file_hash(path: Path) -> str:
    if not path.is_file():
        raise ValueError(f"required artifact missing: {path.name}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _atomic_create_json(path: Path, value: Any) -> None:
    if path.exists():
        raise ValueError(f"FAIL_IF_EXISTS: {path.name}")
    fd, tmp_name = tempfile.mkstemp(prefix=path.name + ".tmp-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, ensure_ascii=False, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.link(tmp_name, path)
    finally:
        if os.path.exists(tmp_name):
            os.unlink(tmp_name)


def _atomic_replace_json(path: Path, value: Any) -> None:
    fd, tmp_name = tempfile.mkstemp(prefix=path.name + ".tmp-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, ensure_ascii=False, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp_name, path)
    finally:
        if os.path.exists(tmp_name):
            os.unlink(tmp_name)


def _safe_child(run_set_dir: Path, name: Any) -> Path:
    if not isinstance(name, str) or not name or Path(name).name != name:
        raise ValueError(f"invalid run-set-local filename: {name!r}")
    return run_set_dir / name


def _load_raw(path: Path) -> list[dict]:
    records: list[dict] = []
    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            record = json.loads(line)
            if not isinstance(record, dict):
                raise ValueError(f"{path.name}:{line_number}: record is not an object")
            records.append(record)
    if not records:
        raise ValueError(f"raw file empty: {path.name}")
    return records


def _expected_metric(scenario: str, mode: str) -> str:
    if scenario == "retrieval":
        functional_mode = mode.split("-c", 1)[0]
        return "retrieval_" + functional_mode.lower()
    metric = METRICS.get(scenario)
    if not metric:
        raise ValueError(f"unsupported Java scenario: {scenario}")
    return metric


def _authoritative_latencies(
    records: list[dict], scenario: str, run_set_id: str, mode: str, run_id: str,
    config_hash: str, environment_hash: str,
) -> list[float]:
    expected_metric = _expected_metric(scenario, mode)
    latencies: list[float] = []
    for index, record in enumerate(records):
        if record.get("scenario") != scenario:
            raise ValueError(f"{mode}/{run_id}: raw scenario linkage mismatch")
        if record.get("run_set_id") != run_set_id:
            raise ValueError(f"{mode}/{run_id}: raw run_set_id linkage mismatch")
        if record.get("run_id") != run_id:
            raise ValueError(f"{mode}/{run_id}: raw run_id linkage mismatch")
        if record.get("config_hash") != config_hash:
            raise ValueError(f"{mode}/{run_id}: raw config_hash linkage mismatch")
        if record.get("environment_hash") != environment_hash:
            raise ValueError(f"{mode}/{run_id}: raw environment_hash linkage mismatch")
        if record.get("metric_name") != expected_metric:
            raise ValueError(f"{mode}/{run_id}: non-authoritative metric at record {index}")
        metadata = record.get("execution_metadata")
        if not isinstance(metadata, dict):
            raise ValueError(f"{mode}/{run_id}: execution_metadata missing")
        if metadata.get("evidence_tag") == "NOT_BENCHMARK_EVIDENCE":
            raise ValueError(f"{mode}/{run_id}: smoke-only evidence tag is ineligible")
        if metadata.get("evidence_mode") != mode:
            raise ValueError(f"{mode}/{run_id}: raw evidence_mode linkage mismatch")
        if record.get("sample_index") != index:
            raise ValueError(f"{mode}/{run_id}: measured sample index mismatch")
        relative = record.get("run_relative_time")
        if isinstance(relative, bool) or not isinstance(relative, (int, float)) or relative < 0:
            raise ValueError(f"{mode}/{run_id}: run_relative_time is invalid")
        if record.get("success") not in (True, False):
            raise ValueError(f"{mode}/{run_id}: success is not boolean")
        latency = record.get("latency_ms")
        if isinstance(latency, bool) or not isinstance(latency, (int, float)):
            raise ValueError(f"{mode}/{run_id}: latency_ms is not numeric")
        latencies.append(float(latency))
    return latencies


def finalize_java_run_set(run_set_dir: Path) -> dict:
    run_set_dir = Path(run_set_dir).resolve()
    manifest_path = run_set_dir / "run_set_manifest.json"
    manifest = _load_json(manifest_path)

    try:
        if manifest.get("schema_version") != JAVA_SCHEMA_VERSION:
            raise ValueError("schema_version must be 1.1")
        if manifest.get("artifact_contract") not in VALID_CONTRACTS:
            raise ValueError("artifact_contract must be COMPONENT or B_CLASS")
        if manifest.get("evidence_purpose") != FORMAL_PURPOSE:
            raise ValueError("only formal benchmark candidates may be finalized")
        if manifest.get("status") != "PARTIAL":
            raise ValueError(f"status={manifest.get('status')}, expected PARTIAL")

        scenario = str(manifest["scenario"])
        run_set_id = str(manifest["run_set_id"])
        config_hash = str(manifest["config_hash"])
        environment_hash = str(manifest["environment_hash"])
        if run_set_dir.name != run_set_id or run_set_dir.parent.name != scenario:
            raise ValueError("run-set directory identity mismatch")
        scenario_contract = SCENARIO_CONTRACTS.get(scenario)
        if scenario_contract is None or manifest.get("artifact_contract") != scenario_contract[0]:
            raise ValueError("artifact_contract/scenario mismatch")

        frozen_path = _safe_child(run_set_dir, manifest.get("frozen_config_file"))
        if _file_hash(frozen_path) != manifest.get("frozen_config_sha256"):
            raise ValueError("frozen config file hash mismatch")
        frozen = _load_json(frozen_path)
        if frozen.get("schema_version") != JAVA_SCHEMA_VERSION \
                or frozen.get("scenario") != scenario \
                or frozen.get("config_hash") != config_hash \
                or frozen.get("config_id") != manifest.get("config_id") \
                or frozen.get("environment_hash") != environment_hash \
                or frozen.get("canonicalization") != "sorted-compact-json-utf8-v1":
            raise ValueError("frozen config wrapper linkage mismatch")
        if frozen.get("application_identity") != {"name": "IntelliDesk", "module": "backend"}:
            raise ValueError("application identity mismatch")
        if _canonical_hash(frozen.get("performance_config")) != config_hash:
            raise ValueError("performance config canonical hash mismatch")
        if _canonical_hash(frozen.get("environment_identity")) != environment_hash:
            raise ValueError("environment identity canonical hash mismatch")
        if scenario == "retrieval":
            performance = frozen.get("performance_config")
            if not isinstance(performance, dict) or any(
                performance.get(field) != value
                for field, value in PERFORMANCE_CONFIG_EXTENSION.items()
            ):
                raise ValueError("retrieval fixture/provider-path performance contract mismatch")
            preflight_path = _safe_child(
                run_set_dir, manifest.get("preflight_observation_file")
            )
            if _file_hash(preflight_path) != manifest.get("preflight_observation_sha256"):
                raise ValueError("retrieval preflight observation hash mismatch")
            validate_retrieval_preflight(manifest, _load_json(preflight_path))

        cleanup_path = _safe_child(run_set_dir, manifest.get("cleanup_observation_file"))
        if _file_hash(cleanup_path) != manifest.get("cleanup_observation_sha256"):
            raise ValueError("cleanup observation hash mismatch")
        cleanup = _load_json(cleanup_path)
        if cleanup.get("schema_version") != JAVA_SCHEMA_VERSION \
                or cleanup.get("run_set_id") != run_set_id \
                or cleanup.get("success") is not True:
            raise ValueError("mandatory harness cleanup did not succeed")

        expected_modes = manifest.get("expected_modes")
        expected_runs = manifest.get("expected_runs_per_mode")
        runs = manifest.get("runs")
        if not isinstance(expected_modes, list) or not expected_modes:
            raise ValueError("expected_modes missing or empty")
        if set(expected_modes) != scenario_contract[1]:
            raise ValueError("expected_modes do not match Java scenario contract")
        if expected_runs != 3:
            raise ValueError("expected_runs_per_mode must be 3")
        if not isinstance(runs, list) or len(runs) != len(expected_modes) * expected_runs:
            raise ValueError("run observation cardinality mismatch")

        # Retrieval is fail-closed at run-set granularity: validate every raw
        # provider-path record before creating even the first percentile file.
        if scenario == "retrieval":
            for run in runs:
                if not isinstance(run, dict):
                    raise ValueError("run link is not an object")
                raw_path = _safe_child(run_set_dir, run.get("raw_file"))
                observation_path = _safe_child(
                    run_set_dir, run.get("run_observation_file")
                )
                if _file_hash(raw_path) != run.get("raw_sha256"):
                    raise ValueError("retrieval raw hash mismatch during fail-closed pre-scan")
                if _file_hash(observation_path) != run.get("run_observation_sha256"):
                    raise ValueError(
                        "retrieval observation hash mismatch during fail-closed pre-scan"
                    )
                mode = str(run.get("mode", ""))
                validate_retrieval_run_observation(
                    mode, _load_json(observation_path)
                )
                records = _load_raw(raw_path)
                validate_retrieval_measured_run(mode, records)
                for record in records:
                    validate_retrieval_sample_metadata(
                        mode, record.get("execution_metadata")
                    )

        identities: set[tuple[str, str]] = set()
        completed_runs: list[dict] = []
        for run in runs:
            if not isinstance(run, dict):
                raise ValueError("run link is not an object")
            mode = str(run.get("mode", ""))
            run_id = str(run.get("run_id", ""))
            identity = (mode, run_id)
            if mode not in expected_modes or not run_id or identity in identities:
                raise ValueError(f"invalid or duplicate run identity: {identity}")
            identities.add(identity)

            raw_path = _safe_child(run_set_dir, run.get("raw_file"))
            observation_path = _safe_child(run_set_dir, run.get("run_observation_file"))
            if _file_hash(raw_path) != run.get("raw_sha256"):
                raise ValueError(f"{mode}/{run_id}: raw hash mismatch")
            if _file_hash(observation_path) != run.get("run_observation_sha256"):
                raise ValueError(f"{mode}/{run_id}: observation hash mismatch")
            observation = _load_json(observation_path)
            if observation.get("schema_version") != JAVA_SCHEMA_VERSION:
                raise ValueError(f"{mode}/{run_id}: observation schema mismatch")
            for field, expected in (("scenario", scenario), ("run_set_id", run_set_id),
                                    ("mode", mode), ("run_id", run_id),
                                    ("raw_file", raw_path.name)):
                if observation.get(field) != expected:
                    raise ValueError(f"{mode}/{run_id}: observation {field} mismatch")

            forbidden = {"warmup_configured", "configured_concurrency", "measured_sample_count",
                         "measured_duration_ms", "timeout_ms", "max_run_duration_ms"}
            if forbidden.intersection(observation):
                raise ValueError(f"{mode}/{run_id}: redundant observation field present")

            records = _load_raw(raw_path)
            measured_contract_evidence = None
            if scenario == "retrieval":
                validate_retrieval_run_observation(mode, observation)
                measured_contract_evidence = validate_retrieval_measured_run(mode, records)
                for record in records:
                    validate_retrieval_sample_metadata(
                        mode, record.get("execution_metadata")
                    )
            latencies = _authoritative_latencies(
                records, scenario, run_set_id, mode, run_id, config_hash, environment_hash
            )
            percentile = {
                "schema_version": JAVA_SCHEMA_VERSION,
                "input": raw_path.name,
                "sample_count": len(latencies),
                "percentiles": compute_percentiles(latencies),
                "method": "nearest-rank",
            }
            if measured_contract_evidence is not None:
                percentile.update(measured_contract_evidence)
            percentile_name = raw_path.name.replace("raw-", "percentiles-", 1).replace(
                ".jsonl", ".json"
            )
            percentile_path = run_set_dir / percentile_name
            _atomic_create_json(percentile_path, percentile)

            complete_link = dict(run)
            complete_link["percentile_file"] = percentile_name
            complete_link["percentile_sha256"] = _file_hash(percentile_path)
            completed_runs.append(complete_link)

        for mode in expected_modes:
            mode_runs = [run for run in completed_runs if run["mode"] == mode]
            if len(mode_runs) != expected_runs:
                raise ValueError(f"mode {mode}: run cardinality mismatch")

        if scenario == "document-processing":
            provenance_path = _safe_child(run_set_dir, manifest.get("runtime_provenance_file"))
            if _file_hash(provenance_path) != manifest.get("runtime_provenance_sha256"):
                raise ValueError("document runtime provenance hash mismatch")
            provenance = _load_json(provenance_path)
            if provenance.get("schema_version") != JAVA_SCHEMA_VERSION \
                    or provenance.get("scenario") != scenario \
                    or provenance.get("run_set_id") != run_set_id:
                raise ValueError("document runtime provenance linkage mismatch")

        manifest["runs"] = completed_runs
        manifest["status"] = "COMPLETE"
        manifest["status_reason"] = "writer artifacts complete; acceptance remains read-only"
        manifest["completed_at"] = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        manifest["final_acceptance_eligible"] = False
        manifest["final_acceptance_status"] = "PENDING_EVIDENCE_VALIDATION"
        _atomic_replace_json(manifest_path, manifest)
        return manifest
    except Exception as error:
        if manifest.get("status") == "PARTIAL":
            manifest["status"] = "FAILED"
            manifest["status_reason"] = f"offline finalization failed: {error}"
            manifest["completed_at"] = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
            _atomic_replace_json(manifest_path, manifest)
        raise


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print("usage: finalize_java_run_set.py <run-set-dir>", file=sys.stderr)
        return 2
    try:
        manifest = finalize_java_run_set(Path(argv[0]))
        print(json.dumps({
            "status": manifest["status"],
            "run_set_id": manifest["run_set_id"],
            "runs": len(manifest["runs"]),
        }, ensure_ascii=False, indent=2))
        return 0
    except Exception as error:
        print(json.dumps({"status": "FAILED", "reason": str(error)}, ensure_ascii=False, indent=2))
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
