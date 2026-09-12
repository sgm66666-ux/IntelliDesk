#!/usr/bin/env python3
"""Tests for run-set acceptance validation.

All tests use temporary directories and synthetic data; no real benchmark
artifacts are modified.
"""

import json
import hashlib
import tempfile
import unittest
from pathlib import Path

from orchestrate_formal_run import finalize_manifest, remove_transient_credentials
from finalize_java_run_set import finalize_java_run_set
from percentile import compute_percentiles
from retrieval_benchmark_contract import contract_for_mode, validate_measured_run
from run_set_acceptance import _expected_java_performance_config, validate_run_set_acceptance


class RunSetAcceptanceTest(unittest.TestCase):

    RAG_TARGET = "/api/workspaces/{workspace_id}/conversations/{conversation_id}/messages/stream"
    DOC_TARGET = "/api/documents/{id}"

    def _write_manifest(self, run_set_dir: Path, scenario: str = "rag-pipeline", **overrides) -> None:
        manifest = {
            "schema_version": "1.0",
            "evidence_class": "bench",
            "scenario": scenario,
            "config_hash": "a" * 64,
            "config_id": "a" * 16,
            "run_set_id": run_set_dir.name,
            "expected_modes": [scenario],
            "expected_runs_per_mode": 3,
            "status": "COMPLETE",
            "measurement_status": "COMPLETE",
            "cleanup_status": "COMPLETE",
        }
        manifest.update(overrides)
        (run_set_dir / "run_set_manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
        )

    def _write_frozen_config(self, run_set_dir: Path, config_hash: str, target: str = RAG_TARGET) -> None:
        scenario = run_set_dir.parent.name
        harness_identity = None
        if scenario == "http-concurrency":
            identity_payload = {
                "schema_version": "1.0",
                "scenario": scenario,
                "behavior_contract": {
                    "authoritative_latency_semantics": "request_duration_ms",
                    "latency_measurement_source": "k6_response_timings_duration",
                    "latency_measurement_unit": "fractional_milliseconds",
                    "latency_request_boundary": "sending_plus_waiting_plus_receiving_excludes_blocked_dns_connect_tls",
                    "latency_pre_percentile_quantization": "none",
                    "percentile_method": "nearest-rank",
                    "sample_collection_semantics": "all bench_req_duration Point samples in sustain stage",
                },
                "components": {
                    "k6_script_template_sha256": "b" * 64,
                    "k6_runner_sha256": "b" * 64,
                    "k6_collector_sha256": "b" * 64,
                    "percentile_sha256": "b" * 64,
                    "orchestrator_sha256": "b" * 64,
                    "validator_sha256": "b" * 64,
                },
            }
            harness_identity = {
                **identity_payload,
                "behavior_hash": hashlib.sha256(
                    json.dumps(
                        identity_payload,
                        sort_keys=True,
                        separators=(",", ":"),
                        ensure_ascii=False,
                    ).encode("utf-8")
                ).hexdigest(),
            }
        frozen = {
            "schema_version": "1.0",
            "scenario": scenario,
            "config_hash": config_hash,
            "k6_version": "k6 fixture v1",
            "environment_identity": {
                "backend_rate_limit_enabled": (
                    "false" if scenario in {"rag-pipeline", "api-key-auth"} else "true"
                ),
                "backend_provider_identity": (
                    {
                        "chat_base_url": "http://provider-stub:18080",
                        "chat_model": "test-model",
                        "embedding_base_url": "http://provider-stub:18080",
                        "embedding_model": "test-model",
                        "rerank_enabled": "false",
                        "rerank_base_url": "unknown",
                        "rerank_model": "unknown",
                    }
                    if scenario == "rag-pipeline" else {}
                ),
            },
            "performance_config": {
                "target": target,
                "percentile_method": "nearest-rank",
                "sample_collection_semantics": "all bench_req_duration Point samples in sustain stage",
                "iteration_rate_policy": "closed-model-ramping-vus",
                "authoritative_latency_semantics": (
                    "first_non_empty_token_ttft_ms" if scenario == "rag-pipeline" else "request_duration_ms"
                ),
                "application_rate_limit_policy": (
                    "disabled_for_controlled_performance_measurement"
                    if scenario in {"rag-pipeline", "api-key-auth"}
                    else "not_applicable_public_health_path"
                ),
                "provider_boundary": (
                    "deterministic_e2e_stub_at_provider_boundary_real_system_pipeline"
                    if scenario == "rag-pipeline" else None
                ),
                "conversation_isolation": (
                    "one_conversation_per_k6_vu" if scenario == "rag-pipeline" else None
                ),
                "post_ttft_settle_delay_ms": 200 if scenario == "rag-pipeline" else None,
                "helper_request_queue_size": 128 if scenario == "rag-pipeline" else None,
                "latency_measurement_source": (
                    "k6_response_timings_duration" if scenario == "http-concurrency" else None
                ),
                "latency_measurement_unit": (
                    "fractional_milliseconds" if scenario == "http-concurrency" else None
                ),
                "latency_request_boundary": (
                    "sending_plus_waiting_plus_receiving_excludes_blocked_dns_connect_tls"
                    if scenario == "http-concurrency" else None
                ),
                "latency_pre_percentile_quantization": (
                    "none" if scenario == "http-concurrency" else None
                ),
            },
        }
        if harness_identity is not None:
            frozen["benchmark_harness_identity"] = harness_identity
        (run_set_dir / "frozen_config.json").write_text(
            json.dumps(frozen, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        if scenario == "http-concurrency":
            preflight = {
                "status": "PASS",
                "resolved_path": "C:\\fixture\\k6.exe",
                "observed_version": "k6 fixture v1",
                "expected_version": "k6 fixture v1",
                "binary_sha256": "c" * 64,
                "resolution_source": "K6_PATH",
            }
            preflight_path = run_set_dir / "launcher_preflight.json"
            preflight_path.write_text(
                json.dumps(preflight, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
            manifest_path = run_set_dir / "run_set_manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest.update({
                "launcher_preflight_file": "launcher_preflight.json",
                "launcher_preflight_sha256": hashlib.sha256(preflight_path.read_bytes()).hexdigest(),
                "k6_resolved_path": preflight["resolved_path"],
                "k6_version": preflight["observed_version"],
                "k6_binary_sha256": preflight["binary_sha256"],
            })
            manifest_path.write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
            )

    def _make_sample(self, success: bool = True, status_code: int = 200,
                     endpoint: str = "http://127.0.0.1:80/api/workspaces/1/conversations/1/messages/stream",
                     ttft: bool = True, run_id: str = "vu1-run-1", scenario: str = "rag-pipeline",
                     phase: str | None = "sustain", latency_ms: float = 15.0) -> dict:
        vu_level = int(run_id.split("-", 1)[0][2:]) if run_id.startswith("vu") else 1
        sample = {
            "timestamp": "2026-08-24T00:00:00Z",
            "scenario": scenario,
            "run_set_id": "rs-1",
            "run_id": run_id,
            "metric_name": "bench_req_duration",
            "endpoint": endpoint,
            "latency_ms": latency_ms,
            "status_code": status_code,
            "success": success,
            "error": None if success else f"status={status_code}",
            "vu": vu_level,
            "concurrency": vu_level,
            "provider_mode": "real",
            "phase": phase,
            "config_hash": "a" * 64,
        }
        if ttft:
            sample["t0_monotonic"] = 1000.0
            sample["t1_monotonic"] = 1000.0 + latency_ms / 1000.0
            sample["ttft_ms"] = latency_ms
            sample["qualifying_event_type"] = "token"
        if scenario == "http-concurrency":
            sample["latency_measurement_source"] = "k6_response_timings_duration"
            sample["latency_request_boundary"] = (
                "sending_plus_waiting_plus_receiving_excludes_blocked_dns_connect_tls"
            )
            sample["latency_pre_percentile_quantization"] = "none"
        return sample

    def _write_raw(self, run_set_dir: Path, scenario: str, mode: str, run_id: str,
                   samples: list[dict]) -> Path:
        path = run_set_dir / f"raw-{scenario}-{mode}-{run_id}.jsonl"
        with open(path, "w", encoding="utf-8") as f:
            for s in samples:
                f.write(json.dumps(s, ensure_ascii=False) + "\n")
        return path

    def _percentile_artifact(self, raw_path: Path, samples: list[dict]) -> dict:
        sustain_latencies = [
            float(sample["latency_ms"])
            for sample in samples
            if sample.get("metric_name") == "bench_req_duration"
            and sample.get("phase") == "sustain"
            and isinstance(sample.get("latency_ms"), (int, float))
        ]
        return {
            "input": str(raw_path),
            "sample_count": len(sustain_latencies),
            "percentiles": compute_percentiles(sustain_latencies) if sustain_latencies else {},
            "method": "nearest-rank",
        }

    def _write_external_run(self, run_set_dir: Path, scenario: str, mode: str,
                            run_id: str, samples: list[dict],
                            write_percentile: bool = True) -> tuple[Path, Path, dict]:
        raw_path = self._write_raw(run_set_dir, scenario, mode, run_id, samples)
        artifact = self._percentile_artifact(raw_path, samples)
        percentile_path = raw_path.with_name(
            raw_path.name.replace("raw-", "percentiles-", 1).replace(".jsonl", ".json")
        )
        if write_percentile:
            percentile_path.write_text(
                json.dumps(artifact, ensure_ascii=False, indent=2), encoding="utf-8"
            )

        vu_level = int(run_id.split("-", 1)[0][2:])
        independent_index = int(run_id.rsplit("-", 1)[1])
        manifest_path = run_set_dir / "run_set_manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest.setdefault("runs", []).append({
            "run_id": run_id,
            "mode": mode,
            "vu_level": vu_level,
            "independent_run_index": independent_index,
            "raw_file": raw_path.name,
            "percentiles": artifact,
        })
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        return raw_path, percentile_path, artifact

    def _create_external_matrix(self, tmp: str, scenario: str = "http-concurrency",
                                sample_factory=None) -> Path:
        run_set_dir = Path(tmp) / scenario / "rs-1"
        run_set_dir.mkdir(parents=True)
        modes = ["bearer-baseline", "api-key"] if scenario == "api-key-auth" else [scenario]
        self._write_manifest(
            run_set_dir,
            scenario=scenario,
            expected_modes=modes,
            expected_runs_per_mode=9,
        )
        target = {
            "http-concurrency": "/api/health",
            "rag-pipeline": self.RAG_TARGET,
            "api-key-auth": "/api/workspaces/{workspace_id}/knowledge-bases",
        }[scenario]
        self._write_frozen_config(run_set_dir, "a" * 64, target=target)
        endpoint = {
            "http-concurrency": "http://127.0.0.1:80/api/health",
            "rag-pipeline": "http://127.0.0.1:80/api/workspaces/1/conversations/1/messages/stream",
            "api-key-auth": "http://127.0.0.1:80/api/workspaces/1/knowledge-bases",
        }[scenario]
        for mode in modes:
            for vu in [1, 5, 10]:
                for index in range(1, 4):
                    run_id = f"vu{vu}-run-{index}"
                    samples = (
                        sample_factory(vu, index, run_id, endpoint)
                        if sample_factory
                        else [
                            self._make_sample(
                                scenario=scenario,
                                run_id=run_id,
                                endpoint=(
                                    f"http://127.0.0.1:80/api/workspaces/1/conversations/{conversation_index + 1}/messages/stream"
                                    if scenario == "rag-pipeline" else endpoint
                                ),
                                ttft=scenario == "rag-pipeline",
                            )
                            for conversation_index in range(vu if scenario == "rag-pipeline" else 1)
                        ]
                    )
                    self._write_external_run(run_set_dir, scenario, mode, run_id, samples)
        return run_set_dir

    def _raw_records(self, raw_path: Path) -> list[dict]:
        return [
            json.loads(line)
            for line in raw_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]

    def _rewrite_raw(self, raw_path: Path, records: list[dict]) -> None:
        raw_path.write_text(
            "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records),
            encoding="utf-8",
        )

    def test_transient_credentials_removed_even_for_failed_run_set_cleanup(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = Path(tmp)
            credential_path = run_set_dir / "runtime_credentials.json"
            credential_path.write_text('{"access_token":"must-not-persist"}', encoding="utf-8")
            remove_transient_credentials(run_set_dir)
            self.assertFalse(credential_path.exists())

    def test_valid_matrix_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            scenario = "document-processing"
            run_set_dir = Path(tmp) / scenario / "rs-1"
            run_set_dir.mkdir(parents=True)
            self._write_manifest(run_set_dir, scenario=scenario)
            self._write_frozen_config(run_set_dir, "a" * 64, target=self.DOC_TARGET)
            for i in range(1, 4):
                self._write_raw(run_set_dir, scenario, scenario, f"run-{i}",
                                [self._make_sample(scenario=scenario, run_id=f"run-{i}",
                                                   endpoint="http://127.0.0.1:80/api/documents/1")])
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("unsupported External", result.reason)

    def test_valid_external_vu_matrix_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            scenario = "rag-pipeline"
            run_set_dir = Path(tmp) / scenario / "rs-1"
            run_set_dir.mkdir(parents=True)
            self._write_manifest(run_set_dir, scenario=scenario, expected_runs_per_mode=9)
            self._write_frozen_config(run_set_dir, "a" * 64, target=self.RAG_TARGET)
            for vu in [1, 5, 10]:
                for i in range(1, 4):
                    run_id = f"vu{vu}-run-{i}"
                    self._write_external_run(
                        run_set_dir, scenario, scenario, run_id,
                        [
                            self._make_sample(
                                run_id=run_id,
                                endpoint=f"http://127.0.0.1:80/api/workspaces/1/conversations/{conversation_index + 1}/messages/stream",
                            )
                            for conversation_index in range(vu)
                        ],
                    )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertTrue(result.eligible, result.reason)

    def test_rag_pipeline_stream_close_latency_cannot_pose_as_ttft(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp, scenario="rag-pipeline")
            raw_path = sorted(run_set_dir.glob("raw-*.jsonl"))[0]
            records = self._raw_records(raw_path)
            records[0]["latency_ms"] = records[0]["ttft_ms"] + 100.0
            self._rewrite_raw(raw_path, records)
            percentile_path = run_set_dir / raw_path.name.replace(
                "raw-", "percentiles-", 1
            ).replace(".jsonl", ".json")
            artifact = self._percentile_artifact(raw_path, records)
            percentile_path.write_text(json.dumps(artifact), encoding="utf-8")
            manifest_path = run_set_dir / "run_set_manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["runs"][0]["percentiles"] = artifact
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("TTFT", result.reason)

    def test_rag_pipeline_shared_conversation_is_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            def shared_conversation_samples(vu, index, run_id, endpoint):
                return [self._make_sample(run_id=run_id, endpoint=endpoint)]

            run_set_dir = self._create_external_matrix(
                tmp, scenario="rag-pipeline", sample_factory=shared_conversation_samples
            )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("isolated conversation endpoints", result.reason)

    def test_valid_api_key_external_paired_matrix_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp, scenario="api-key-auth")
            result = validate_run_set_acceptance(run_set_dir)
            self.assertTrue(result.eligible, result.reason)
            self.assertEqual(
                {mode["mode"] for mode in result.details["mode_details"]},
                {"bearer-baseline", "api-key"},
            )

    def test_all_500_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            scenario = "document-processing"
            run_set_dir = Path(tmp) / scenario / "rs-1"
            run_set_dir.mkdir(parents=True)
            self._write_manifest(run_set_dir, scenario=scenario)
            self._write_frozen_config(run_set_dir, "a" * 64, target=self.DOC_TARGET)
            for i in range(1, 4):
                self._write_raw(run_set_dir, scenario, scenario, f"run-{i}",
                                [self._make_sample(scenario=scenario, success=False, status_code=500,
                                                   run_id=f"run-{i}", endpoint="http://127.0.0.1:80/api/documents/1")])
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("unsupported External", result.reason)

    def test_missing_ttft_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            scenario = "document-processing"
            run_set_dir = Path(tmp) / scenario / "rs-1"
            run_set_dir.mkdir(parents=True)
            self._write_manifest(run_set_dir, scenario=scenario)
            self._write_frozen_config(run_set_dir, "a" * 64, target=self.DOC_TARGET)
            sample = self._make_sample(scenario=scenario, ttft=False, endpoint="http://127.0.0.1:80/api/documents/1")
            sample["qualifying_event_type"] = "token"
            self._write_raw(run_set_dir, scenario, scenario, "run-1", [sample])
            for i in range(2, 4):
                self._write_raw(run_set_dir, scenario, scenario, f"run-{i}",
                                [self._make_sample(scenario=scenario, run_id=f"run-{i}",
                                                   endpoint="http://127.0.0.1:80/api/documents/1")])
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("unsupported External", result.reason)

    def test_less_than_three_runs_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            scenario = "document-processing"
            run_set_dir = Path(tmp) / scenario / "rs-1"
            run_set_dir.mkdir(parents=True)
            self._write_manifest(run_set_dir, scenario=scenario, expected_runs_per_mode=3)
            self._write_frozen_config(run_set_dir, "a" * 64, target=self.DOC_TARGET)
            self._write_raw(run_set_dir, scenario, scenario, "run-1",
                            [self._make_sample(scenario=scenario, endpoint="http://127.0.0.1:80/api/documents/1")])
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("unsupported External", result.reason)

    def test_wrong_target_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            scenario = "document-processing"
            run_set_dir = Path(tmp) / scenario / "rs-1"
            run_set_dir.mkdir(parents=True)
            self._write_manifest(run_set_dir, scenario=scenario)
            self._write_frozen_config(run_set_dir, "a" * 64, target=self.DOC_TARGET)
            for i in range(1, 4):
                self._write_raw(run_set_dir, scenario, scenario, f"run-{i}",
                                [self._make_sample(scenario=scenario, endpoint="http://127.0.0.1:80/ping",
                                                   run_id=f"run-{i}")])
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("unsupported External", result.reason)

    def test_wrong_config_hash_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            scenario = "document-processing"
            run_set_dir = Path(tmp) / scenario / "rs-1"
            run_set_dir.mkdir(parents=True)
            self._write_manifest(run_set_dir, scenario=scenario, config_hash="a" * 64)
            self._write_frozen_config(run_set_dir, "b" * 64, target=self.DOC_TARGET)
            for i in range(1, 4):
                self._write_raw(run_set_dir, scenario, scenario, f"run-{i}",
                                [self._make_sample(scenario=scenario, run_id=f"run-{i}",
                                                   endpoint="http://127.0.0.1:80/api/documents/1")])
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("unsupported External", result.reason)

    def test_cleanup_failed_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            scenario = "document-processing"
            run_set_dir = Path(tmp) / scenario / "rs-1"
            run_set_dir.mkdir(parents=True)
            self._write_manifest(run_set_dir, scenario=scenario, cleanup_status="FAILED")
            self._write_frozen_config(run_set_dir, "a" * 64, target=self.DOC_TARGET)
            for i in range(1, 4):
                self._write_raw(run_set_dir, scenario, scenario, f"run-{i}",
                                [self._make_sample(scenario=scenario, run_id=f"run-{i}",
                                                   endpoint="http://127.0.0.1:80/api/documents/1")])
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("unsupported External", result.reason)

    def test_missing_manifest_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            scenario = "document-processing"
            run_set_dir = Path(tmp) / scenario / "rs-1"
            run_set_dir.mkdir(parents=True)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("manifest missing", result.reason)

    def test_empty_raw_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            scenario = "document-processing"
            run_set_dir = Path(tmp) / scenario / "rs-1"
            run_set_dir.mkdir(parents=True)
            self._write_manifest(run_set_dir, scenario=scenario)
            self._write_frozen_config(run_set_dir, "a" * 64, target=self.DOC_TARGET)
            for i in range(1, 4):
                self._write_raw(run_set_dir, scenario, scenario, f"run-{i}", [])
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("unsupported External", result.reason)

    def test_missing_vu_level_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            scenario = "rag-pipeline"
            run_set_dir = Path(tmp) / scenario / "rs-1"
            run_set_dir.mkdir(parents=True)
            self._write_manifest(run_set_dir, scenario=scenario, expected_runs_per_mode=3)
            self._write_frozen_config(run_set_dir, "a" * 64, target=self.RAG_TARGET)
            for i in range(1, 4):
                run_id = f"vu1-run-{i}"
                self._write_external_run(
                    run_set_dir, scenario, scenario, run_id,
                    [
                        self._make_sample(
                            run_id=run_id,
                            endpoint=f"http://127.0.0.1:80/api/workspaces/1/conversations/{conversation_index + 1}/messages/stream",
                        )
                        for conversation_index in range(1)
                    ],
                )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("missing mandatory VU levels", result.reason)

    def test_less_than_three_independent_runs_per_level_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            scenario = "rag-pipeline"
            run_set_dir = Path(tmp) / scenario / "rs-1"
            run_set_dir.mkdir(parents=True)
            self._write_manifest(run_set_dir, scenario=scenario, expected_runs_per_mode=8)
            self._write_frozen_config(run_set_dir, "a" * 64, target=self.RAG_TARGET)
            # VU 1 and 10 have 3 runs, VU 5 has only 2.
            runs = [(1, 1), (1, 2), (1, 3), (5, 1), (5, 2), (10, 1), (10, 2), (10, 3)]
            for vu, idx in runs:
                run_id = f"vu{vu}-run-{idx}"
                self._write_external_run(
                    run_set_dir, scenario, scenario, run_id,
                    [
                        self._make_sample(
                            run_id=run_id,
                            endpoint=f"http://127.0.0.1:80/api/workspaces/1/conversations/{conversation_index + 1}/messages/stream",
                        )
                        for conversation_index in range(vu)
                    ],
                )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("VU 5 has 2 independent runs", result.reason)

    def test_external_missing_phase_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            raw_path = sorted(run_set_dir.glob("raw-*.jsonl"))[0]
            records = self._raw_records(raw_path)
            records[0].pop("phase")
            self._rewrite_raw(raw_path, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("invalid phase", result.reason)

    def test_external_null_phase_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            raw_path = sorted(run_set_dir.glob("raw-*.jsonl"))[0]
            records = self._raw_records(raw_path)
            records[0]["phase"] = None
            self._rewrite_raw(raw_path, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("invalid phase", result.reason)

    def test_external_empty_phase_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            raw_path = sorted(run_set_dir.glob("raw-*.jsonl"))[0]
            records = self._raw_records(raw_path)
            records[0]["phase"] = ""
            self._rewrite_raw(raw_path, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("invalid phase", result.reason)

    def test_external_unknown_phase_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            raw_path = sorted(run_set_dir.glob("raw-*.jsonl"))[0]
            records = self._raw_records(raw_path)
            records[0]["phase"] = "unknown"
            self._rewrite_raw(raw_path, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("invalid phase", result.reason)

    def test_external_all_ramp_zero_sustain_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            for raw_path in run_set_dir.glob("raw-*.jsonl"):
                records = self._raw_records(raw_path)
                for record in records:
                    record["phase"] = "ramp"
                self._rewrite_raw(raw_path, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("zero valid sustain", result.reason)

    def test_external_single_required_run_zero_sustain_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            raw_path = sorted(run_set_dir.glob("raw-*.jsonl"))[0]
            records = self._raw_records(raw_path)
            for record in records:
                record["phase"] = "ramp"
            self._rewrite_raw(raw_path, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("zero valid sustain", result.reason)

    def test_external_missing_percentile_artifact_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            percentile_path = sorted(run_set_dir.glob("percentiles-*.json"))[0]
            percentile_path.unlink()
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("percentile artifact missing", result.reason)

    def test_external_mismatched_percentile_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            percentile_path = sorted(run_set_dir.glob("percentiles-*.json"))[0]
            artifact = json.loads(percentile_path.read_text(encoding="utf-8"))
            artifact["percentiles"]["p99"] = 9999.0
            percentile_path.write_text(
                json.dumps(artifact, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("sustain-only raw nearest-rank recompute", result.reason)

    def test_external_wrong_percentile_sample_count_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            percentile_path = sorted(run_set_dir.glob("percentiles-*.json"))[0]
            artifact = json.loads(percentile_path.read_text(encoding="utf-8"))
            artifact["sample_count"] += 1
            percentile_path.write_text(json.dumps(artifact), encoding="utf-8")
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("sample_count", result.reason)

    def test_external_wrong_percentile_input_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            percentile_path = sorted(run_set_dir.glob("percentiles-*.json"))[0]
            artifact = json.loads(percentile_path.read_text(encoding="utf-8"))
            artifact["input"] = "raw-wrong.jsonl"
            percentile_path.write_text(json.dumps(artifact), encoding="utf-8")
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("input does not match", result.reason)

    def test_external_wrong_percentile_method_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            percentile_path = sorted(run_set_dir.glob("percentiles-*.json"))[0]
            artifact = json.loads(percentile_path.read_text(encoding="utf-8"))
            artifact["method"] = "linear-interpolation"
            percentile_path.write_text(json.dumps(artifact), encoding="utf-8")
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("method must be nearest-rank", result.reason)

    def test_external_wrong_raw_scenario_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            raw_path = sorted(run_set_dir.glob("raw-*.jsonl"))[0]
            records = self._raw_records(raw_path)
            records[0]["scenario"] = "wrong"
            self._rewrite_raw(raw_path, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("raw scenario linkage mismatch", result.reason)

    def test_external_wrong_raw_run_set_id_not_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            raw_path = sorted(run_set_dir.glob("raw-*.jsonl"))[0]
            records = self._raw_records(raw_path)
            records[0]["run_set_id"] = "wrong"
            self._rewrite_raw(raw_path, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("raw run_set_id linkage mismatch", result.reason)

    def test_external_manifest_raw_run_vu_linkages_not_eligible(self):
        mutations = {
            "raw_file": ("wrong.jsonl", "manifest raw_file linkage mismatch"),
            "run_id": ("wrong-run", "manifest run record missing"),
            "vu_level": (5, "manifest VU linkage mismatch"),
            "independent_run_index": (2, "manifest independent-run linkage mismatch"),
        }
        for field, (value, expected_reason) in mutations.items():
            with self.subTest(field=field), tempfile.TemporaryDirectory() as tmp:
                run_set_dir = self._create_external_matrix(tmp)
                manifest_path = run_set_dir / "run_set_manifest.json"
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                manifest["runs"][0][field] = value
                manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
                result = validate_run_set_acceptance(run_set_dir)
                self.assertFalse(result.eligible)
                self.assertIn(expected_reason, result.reason)

    def test_external_ramp_cannot_influence_accepted_percentile(self):
        def mixed_samples(vu, index, run_id, endpoint):
            values = [
                ("ramp", 9999.0),
                ("sustain", 10.0),
                ("sustain", 20.0),
                ("sustain", 30.0),
                ("sustain", 40.0),
                ("cooldown", 8888.0),
            ]
            return [
                self._make_sample(
                    scenario="http-concurrency",
                    run_id=run_id,
                    endpoint=endpoint,
                    ttft=False,
                    phase=phase,
                    latency_ms=latency,
                )
                for phase, latency in values
            ]

        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp, sample_factory=mixed_samples)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertTrue(result.eligible, result.reason)
            for run in result.details["mode_details"][0]["runs"]:
                self.assertEqual(
                    run["percentiles"],
                    {"p50": 20.0, "p90": 40.0, "p95": 40.0, "p99": 40.0},
                )

    def test_external_non_latency_metadata_does_not_require_phase(self):
        def samples_with_metadata(vu, index, run_id, endpoint):
            return [
                {"record_type": "metadata", "event": "run-marker"},
                self._make_sample(
                    scenario="http-concurrency",
                    run_id=run_id,
                    endpoint=endpoint,
                    ttft=False,
                ),
            ]

        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(
                tmp,
                sample_factory=samples_with_metadata,
            )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertTrue(result.eligible, result.reason)

    def test_external_sustain_metadata_latency_cannot_pollute_percentiles(self):
        def samples_with_polluting_metadata(vu, index, run_id, endpoint):
            return [
                {
                    "record_type": "metadata",
                    "event": "run-marker",
                    "phase": "sustain",
                    "latency_ms": 9999.0,
                },
                self._make_sample(
                    scenario="http-concurrency",
                    run_id=run_id,
                    endpoint=endpoint,
                    ttft=False,
                    latency_ms=15.0,
                ),
            ]

        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(
                tmp, sample_factory=samples_with_polluting_metadata
            )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertTrue(result.eligible, result.reason)
            for run in result.details["mode_details"][0]["runs"]:
                self.assertEqual(
                    run["percentiles"],
                    {"p50": 15.0, "p90": 15.0, "p95": 15.0, "p99": 15.0},
                )

    def test_manifest_complete_is_necessary_but_not_sufficient(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            manifest_path = run_set_dir / "run_set_manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["status"] = "PARTIAL"
            manifest_path.write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("manifest status", result.reason)

    def test_http_concurrency_fractional_raw_recomputes_consistently(self):
        values = [0.8, 1.01, 1.1, 1.4, 1.99]

        def fractional_samples(vu, index, run_id, endpoint):
            return [
                self._make_sample(
                    scenario="http-concurrency",
                    run_id=run_id,
                    endpoint=endpoint,
                    ttft=False,
                    latency_ms=value,
                )
                for value in values
            ]

        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp, sample_factory=fractional_samples)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertTrue(result.eligible, result.reason)
            expected = {"p50": 1.1, "p90": 1.99, "p95": 1.99, "p99": 1.99}
            for run in result.details["mode_details"][0]["runs"]:
                self.assertEqual(expected, run["percentiles"])

    def test_http_concurrency_missing_precision_provenance_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            raw_path = next(run_set_dir.glob("raw-http-concurrency-*.jsonl"))
            records = self._raw_records(raw_path)
            records[0].pop("latency_measurement_source")
            self._rewrite_raw(raw_path, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("latency_measurement_source mismatch", result.reason)

    def test_http_concurrency_missing_launcher_preflight_linkage_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            manifest_path = run_set_dir / "run_set_manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest.pop("launcher_preflight_file")
            manifest_path.write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("launcher preflight linkage missing", result.reason)

    def test_http_concurrency_tampered_launcher_preflight_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_external_matrix(tmp)
            preflight_path = run_set_dir / "launcher_preflight.json"
            preflight = json.loads(preflight_path.read_text(encoding="utf-8"))
            preflight["observed_version"] = "tampered"
            preflight_path.write_text(
                json.dumps(preflight, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("preflight hash mismatch", result.reason)

    def test_orchestrator_complete_manifest_stays_pending_acceptance(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = Path(tmp) / "http-concurrency" / "rs-1"
            run_set_dir.mkdir(parents=True)
            finalize_manifest(
                run_set_dir=run_set_dir,
                scenario="http-concurrency",
                run_set_id="rs-1",
                config_hash="a" * 64,
                expected_modes=["http-concurrency"],
                runs=[],
                measurement_status="COMPLETE",
                cleanup_status="COMPLETE",
            )
            manifest = json.loads(
                (run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8")
            )
            self.assertEqual(manifest["status"], "COMPLETE")
            self.assertFalse(manifest["final_acceptance_eligible"])
            self.assertEqual(
                manifest["final_acceptance_status"],
                "PENDING_EVIDENCE_VALIDATION",
            )
            original = (run_set_dir / "run_set_manifest.json").read_bytes()
            with self.assertRaises(FileExistsError):
                finalize_manifest(
                    run_set_dir=run_set_dir,
                    scenario="http-concurrency",
                    run_set_id="rs-1",
                    config_hash="b" * 64,
                    expected_modes=["http-concurrency"],
                    runs=[],
                    measurement_status="FAILED",
                    cleanup_status="FAILED",
                )
            self.assertEqual(original, (run_set_dir / "run_set_manifest.json").read_bytes())

    def test_formal_http_008_hash_is_unchanged_and_current_strict_validation_is_ineligible(self):
        project_root = Path(__file__).resolve().parents[2]
        run_set_dir = (
            project_root / "docs" / "evaluation" / "bench" / "http-concurrency"
            / "a-formal-http-concurrency-008"
        )
        manifest_path = run_set_dir / "run_set_manifest.json"
        frozen_path = run_set_dir / "frozen_config.json"
        self.assertEqual(
            "460efe7f549723637faa3f94d540326cf897a2008a15652c92f24502e7a27a32",
            hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
        )
        self.assertEqual(
            "b1709bf5759e42017f651061e18fdaa223f8dc53de5656ca03ce365954aa565b",
            hashlib.sha256(frozen_path.read_bytes()).hexdigest(),
        )
        result = validate_run_set_acceptance(run_set_dir)
        self.assertFalse(result.eligible)

    def test_http_vu5_diagnostic_directory_is_not_formal_evidence(self):
        project_root = Path(__file__).resolve().parents[2]
        diagnostic_dir = (
            project_root / "docs" / "evaluation" / "diagnostics" / "http-concurrency"
            / "diag-http-vu5-repeatability-20260910-001"
        )
        manifest_path = diagnostic_dir / "artifact-manifest.json"
        self.assertEqual(
            "9881357f91bf7a9cb6e4dc67f0b1686309ed30465818d4a0332223d0504d5b9c",
            hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
        )
        result = validate_run_set_acceptance(diagnostic_dir)
        self.assertFalse(result.eligible)
        self.assertIn("manifest missing", result.reason)


class JavaRunSetAcceptanceTest(unittest.TestCase):

    SPECS = {
        "retrieval": {
            "contract": "COMPONENT",
            "modes": [f"{mode}-c{c}" for mode in ("VECTOR", "BM25", "HYBRID", "RERANK") for c in (1, 4, 8)],
            "samples": 1000,
            "warmup_min_samples": 200,
            "warmup_min_operation_duration_ms": 5000,
            "provider": "real",
        },
        "api-key-auth": {
            "contract": "COMPONENT",
            "modes": ["auth-component-c1", "auth-component-c4", "auth-component-c8"],
            "samples": 1000,
            "warmup": 200,
            "provider": "real",
        },
        "rag-completion": {
            "contract": "B_CLASS", "modes": ["e2e-stub"], "samples": 30,
            "warmup": 1, "provider": "e2e-deterministic-stub", "timeout": 60000, "max": 2400000,
        },
        "document-processing": {
            "contract": "B_CLASS", "modes": ["e2e-real"], "samples": 20,
            "warmup": 0, "provider": "real", "timeout": 120000, "max": 2700000,
        },
        "agent-tool-flow": {
            "contract": "B_CLASS", "modes": ["e2e-stub"], "samples": 30,
            "warmup": 1, "provider": "e2e-deterministic-stub", "timeout": 90000, "max": 3600000,
        },
    }

    @staticmethod
    def _canonical_hash(value: dict) -> str:
        return hashlib.sha256(json.dumps(
            value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")).hexdigest()

    @staticmethod
    def _file_hash(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    @staticmethod
    def _write_json(path: Path, value: dict) -> None:
        path.write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    def _metric(self, scenario: str, mode: str) -> str:
        if scenario == "retrieval":
            return "retrieval_" + mode.split("-c", 1)[0].lower()
        return {
            "api-key-auth": "auth_duration",
            "rag-completion": "completion_duration",
            "document-processing": "processing_duration",
            "agent-tool-flow": "completion_duration",
        }[scenario]

    def _create_candidate(self, root: str, scenario: str, *, smoke_tag: bool = False,
                          cleanup_success: bool = True, finalize: bool = True) -> Path:
        spec = self.SPECS[scenario]
        run_set_dir = Path(root) / scenario / "java-formal-001"
        run_set_dir.mkdir(parents=True)
        config = _expected_java_performance_config(scenario)
        environment = {"os_name": "test", "java_version": "21", "fixture": True}
        config_hash = self._canonical_hash(config)
        environment_hash = self._canonical_hash(environment)

        frozen = {
            "schema_version": "1.1", "scenario": scenario, "config_hash": config_hash,
            "config_id": config_hash[:16], "environment_hash": environment_hash,
            "frozen_at": "2026-08-28T00:00:00Z",
            "canonicalization": "sorted-compact-json-utf8-v1",
            "application_identity": {"name": "IntelliDesk", "module": "backend"},
            "environment_identity": environment, "performance_config": config,
        }
        frozen_path = run_set_dir / "frozen_config.json"
        self._write_json(frozen_path, frozen)

        runs = []
        measured_document_ids = []
        document_facts = []
        next_document_id = 1000
        for mode in spec["modes"]:
            concurrency = int(mode.rsplit("-c", 1)[1]) if "-c" in mode else 1
            retrieval_contract = contract_for_mode(mode) if scenario == "retrieval" else None
            sample_count = (
                retrieval_contract[2]["measured_min_operations"]
                if retrieval_contract is not None else spec["samples"]
            )
            for run_index in range(1, 4):
                run_id = f"run-{run_index}"
                raw_name = f"raw-{scenario}-{mode}-{run_id}.jsonl"
                raw_path = run_set_dir / raw_name
                with raw_path.open("w", encoding="utf-8", newline="\n") as stream:
                    for sample_index in range(sample_count):
                        metadata = {"evidence_mode": mode, "sample_type": "measured"}
                        if scenario == "retrieval":
                            functional_mode = mode.split("-c", 1)[0]
                            metadata.update({
                                "result_count": 1,
                                "vector_candidate_count": 1 if functional_mode in {"VECTOR", "HYBRID", "RERANK"} else 0,
                                "bm25_candidate_count": 1 if functional_mode in {"BM25", "HYBRID", "RERANK"} else 0,
                                "hybrid_candidate_count": 1 if functional_mode in {"HYBRID", "RERANK"} else 0,
                                "candidate_count_before_hydration": 1,
                                "hydrated_result_count": 1,
                                "rerank_candidate_count": 1 if functional_mode == "RERANK" else -1,
                                "rerank_executed": functional_mode == "RERANK",
                                "rerank_provider_call_count": 1 if functional_mode == "RERANK" else 0,
                                "post_rerank_hydrated_result_count": 1 if functional_mode == "RERANK" else -1,
                                "request_timeout_ms": retrieval_contract[2]["request_timeout_ms"],
                            })
                        if spec["contract"] == "B_CLASS":
                            metadata["request_timeout_ms"] = spec["timeout"]
                        if smoke_tag:
                            metadata["evidence_tag"] = "NOT_BENCHMARK_EVIDENCE"
                        if scenario == "agent-tool-flow":
                            metadata.update({"tool_call_count": 1, "tool_result_count": 1})
                        if scenario == "document-processing":
                            document_id = next_document_id
                            next_document_id += 1
                            metadata["document_id"] = document_id
                            measured_document_ids.append(document_id)
                            document_facts.append({
                                "observed_at": "2026-08-28T00:00:01Z",
                                "source": "live persistence entities/MinioClient.statObject/parser registry",
                                "probe": "post-terminal-document-fact-chain",
                                "document_id": document_id, "document_status": "COMPLETED",
                                "document_completed_at": "2026-08-28T00:00:01",
                                "document_index_task_id": document_id + 10000,
                                "document_index_message_id": f"message-{document_id}",
                                "document_index_task_status": "SUCCEEDED",
                                "document_index_attempt_count": 1,
                                "parser_format": "TEXT", "parser_class": "PlainTextDocumentParser",
                                "parser_metadata_sha256": "a" * 64,
                                "chunk_count": 1, "chunk_digest": "b" * 64,
                                "retrieval_task_id": document_id + 20000,
                                "retrieval_task_status": "READY", "retrieval_generation": 1,
                                "embedding_model": "fixture-model", "embedding_dimension": 3,
                                "elasticsearch_index": "fixture-index", "indexed_chunk_count": 1,
                                "minio_bucket": "fixture-bucket", "minio_object_key_sha256": "c" * 64,
                                "minio_object_size": 100, "minio_object_etag": "fixture-etag",
                            })
                        is_real_rerank = scenario == "retrieval" and mode.startswith("RERANK-")
                        latency_ms = 6000.0 if is_real_rerank else 10.0
                        run_relative_time = (
                            6000.0 * sample_index
                            if is_real_rerank
                            else 5000.0 * (sample_index + 1) / sample_count
                        )
                        record = {
                            "timestamp": "2026-08-28T00:00:00Z",
                            "run_relative_time": run_relative_time,
                            "scenario": scenario, "run_set_id": run_set_dir.name, "run_id": run_id,
                            "sample_index": sample_index, "metric_name": self._metric(scenario, mode),
                            "endpoint": f"e2e://{scenario}", "latency_ms": latency_ms,
                            "status_code": 200, "success": True, "error": None,
                            "concurrency": concurrency, "provider_mode": spec["provider"],
                            "config_hash": config_hash, "environment_hash": environment_hash,
                            "execution_metadata": metadata,
                        }
                        stream.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
                observation_name = f"observation-{mode}-{run_id}.json"
                observation_path = run_set_dir / observation_name
                observation = {
                    "schema_version": "1.1", "scenario": scenario, "run_set_id": run_set_dir.name,
                    "mode": mode, "run_id": run_id, "raw_file": raw_name,
                    "observed_at": "2026-08-28T00:00:02Z",
                    "max_in_flight_observed": concurrency,
                }
                if scenario == "retrieval":
                    contract_class, provider_path_class, contract = retrieval_contract
                    observation.update({
                        "contract_class": contract_class,
                        "provider_path_class": provider_path_class,
                        "warmup_completed_operations": contract["warmup_min_operations"],
                        "warmup_operation_duration_ms": contract["warmup_min_operation_duration_ms"],
                        "measurement_started_after_warmup": True,
                    })
                else:
                    observation["warmup_completed"] = spec["warmup"]
                self._write_json(observation_path, observation)
                runs.append({
                    "mode": mode, "run_id": run_id, "raw_file": raw_name,
                    "raw_sha256": self._file_hash(raw_path), "run_observation_file": observation_name,
                    "run_observation_sha256": self._file_hash(observation_path),
                })

        cleanup_path = run_set_dir / "cleanup_observation.json"
        self._write_json(cleanup_path, {"schema_version": "1.1", "run_set_id": run_set_dir.name,
                                        "completed_at": "2026-08-28T00:00:03Z",
                                        "success": cleanup_success, "facts": {"fixture_cleanup": True}})
        manifest = {
            "schema_version": "1.1", "evidence_class": "bench", "scenario": scenario,
            "artifact_contract": spec["contract"], "evidence_purpose": "FORMAL_BENCHMARK_CANDIDATE",
            "config_hash": config_hash, "config_id": config_hash[:16], "environment_hash": environment_hash,
            "run_set_id": run_set_dir.name, "expected_modes": spec["modes"], "expected_runs_per_mode": 3,
            "status": "PARTIAL", "frozen_config_file": frozen_path.name,
            "frozen_config_sha256": self._file_hash(frozen_path), "cleanup_observation_file": cleanup_path.name,
            "cleanup_observation_sha256": self._file_hash(cleanup_path),
            "percentile_authority": "python-offline-nearest-rank", "runs": runs,
        }
        if scenario == "retrieval":
            facts = {
                "databaseReachable": True, "workspaceExists": True,
                "knowledgeBaseExists": True, "completedDocumentCount": 14,
                "chunkCount": 42, "embeddedChunkCount": 42,
                "minEmbeddingDimension": 1536, "maxEmbeddingDimension": 1536,
                "readyTaskCount": 14, "generationMatchedChunkCount": 42,
                "fixtureHashMatches": True, "scopeMatches": True,
                "queryRelationshipsComplete": True, "elasticsearchReachable": True,
                "elasticsearchIndexExists": True, "elasticsearchDocumentCount": 42,
                "elasticsearchIdentityMatchesPostgres": True,
                "elasticsearchContentHashesMatch": True,
            }
            aggregates = {
                mode: {
                    "queries_checked": 69,
                    "minimum_candidate_count_before_hydration": 1,
                    "minimum_hydrated_result_count": 1,
                    "minimum_final_result_count": 1,
                }
                for mode in ("VECTOR", "BM25", "HYBRID", "RERANK")
            }
            preflight_path = run_set_dir / "preflight_observation.json"
            self._write_json(preflight_path, {
                "schema_version": "1.1", "scenario": "retrieval",
                "run_set_id": run_set_dir.name, "classification": "NOT_BENCHMARK_EVIDENCE",
                "formal_percentile_use_prohibited": True,
                "observed_at": "2026-08-28T00:00:00Z", "success": True,
                "fixture_version": "retrieval-benchmark-fixture-v1",
                "fixture_hash": "6528792f29b3c910d1a126fcae3f50b6a04b0d0c7b7d06a307dbcf9b23aa69a2",
                "document_count": 14, "chunk_count": 42,
                "query_relationships_checked": 69, "fixture_facts": facts,
                "mode_path_aggregates": aggregates, "rerank_provider_call_count": 69,
                "reranker_quality_config_hash": "9cd3832be43b467c9591d9c4d94ebceb3cb851ca85210e98383aca94895c7f11",
                "rerank_provider_identity": {
                    "model_id": "BAAI/bge-reranker-v2-m3",
                    "reranker_quality_config_hash": "9cd3832be43b467c9591d9c4d94ebceb3cb851ca85210e98383aca94895c7f11",
                    "live_identity_fields_match": True,
                    "provider_info_sha256": "d" * 64,
                },
            })
            manifest["preflight_observation_file"] = preflight_path.name
            manifest["preflight_observation_sha256"] = self._file_hash(preflight_path)
        if scenario == "document-processing":
            provenance_path = run_set_dir / "runtime_provenance.json"
            def service_snapshot(phase: str) -> dict:
                queue = {
                    "observed_at": "2026-08-28T00:00:00Z", "source": "queueDeclarePassive",
                    "probe": "live_queue_declare_passive", "name": "fixture-queue",
                    "message_count": 0, "consumer_count": 1,
                }
                return {
                    "observed_at": "2026-08-28T00:00:00Z", "source": "live runtime clients",
                    "capture_phase": phase,
                    "jdbc": {
                        "observed_at": "2026-08-28T00:00:00Z", "source": "DataSource/JdbcTemplate",
                        "probe": "live_connection", "valid": True, "select_one": 1,
                        "database_product": "PostgreSQL", "database_version": "fixture",
                        "driver_name": "fixture", "driver_version": "fixture",
                    },
                    "minio": {
                        "observed_at": "2026-08-28T00:00:00Z", "source": "MinioClient.bucketExists",
                        "probe": "live_bucket_exists", "endpoint": "http://minio:9000",
                        "bucket": "fixture-bucket", "bucket_exists": True,
                    },
                    "rabbitmq": {
                        "observed_at": "2026-08-28T00:00:00Z", "source": "RabbitTemplate/listenerRegistry",
                        "probe": "live_connection_queue_listener", "server_product": "RabbitMQ",
                        "server_version": "fixture", "server_platform": "fixture", "open": True,
                        "document_queue": dict(queue), "retrieval_queue": dict(queue),
                        "listener_containers": 2, "listeners_running": 2,
                    },
                }
            self._write_json(provenance_path, {
                "schema_version": "1.1", "scenario": scenario, "run_set_id": run_set_dir.name,
                "captured_at": "2026-08-28T00:00:02Z",
                "measured_document_ids": measured_document_ids,
                "pre_measurement": service_snapshot("pre-measurement"),
                "documents": document_facts,
                "final_snapshot": service_snapshot("pre-finalization"),
            })
            manifest["runtime_provenance_file"] = provenance_path.name
            manifest["runtime_provenance_sha256"] = self._file_hash(provenance_path)
        self._write_json(run_set_dir / "run_set_manifest.json", manifest)
        if finalize:
            finalize_java_run_set(run_set_dir)
        return run_set_dir

    def _rewrite_java_raw(self, run_set_dir: Path, run_index: int, records: list[dict]) -> None:
        manifest_path = run_set_dir / "run_set_manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        run = manifest["runs"][run_index]
        raw_path = run_set_dir / run["raw_file"]
        raw_path.write_text(
            "".join(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n" for record in records),
            encoding="utf-8",
        )
        run["raw_sha256"] = self._file_hash(raw_path)
        percentile_path = run_set_dir / run["percentile_file"]
        artifact = json.loads(percentile_path.read_text(encoding="utf-8"))
        latencies = [float(record["latency_ms"]) for record in records]
        artifact["sample_count"] = len(latencies)
        artifact["percentiles"] = compute_percentiles(latencies) if latencies else {}
        if run_set_dir.parent.name == "retrieval":
            try:
                artifact.update(validate_measured_run(run["mode"], records))
            except ValueError:
                # Negative-path tests deliberately create contract-ineligible raw.
                # Acceptance rejects that evidence before reading its sidecar.
                pass
        self._write_json(percentile_path, artifact)
        run["percentile_sha256"] = self._file_hash(percentile_path)
        self._write_json(manifest_path, manifest)

    def _mutate_java_observation(self, run_set_dir: Path, mutator) -> None:
        manifest_path = run_set_dir / "run_set_manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        run = manifest["runs"][0]
        path = run_set_dir / run["run_observation_file"]
        observation = json.loads(path.read_text(encoding="utf-8"))
        mutator(observation)
        self._write_json(path, observation)
        run["run_observation_sha256"] = self._file_hash(path)
        self._write_json(manifest_path, manifest)

    def _rechain_java_performance_config(self, run_set_dir: Path, mutator) -> None:
        manifest_path = run_set_dir / "run_set_manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        frozen_path = run_set_dir / manifest["frozen_config_file"]
        frozen = json.loads(frozen_path.read_text(encoding="utf-8"))
        mutator(frozen["performance_config"])
        config_hash = self._canonical_hash(frozen["performance_config"])
        frozen["config_hash"] = config_hash
        frozen["config_id"] = config_hash[:16]
        manifest["config_hash"] = config_hash
        manifest["config_id"] = config_hash[:16]
        for run in manifest["runs"]:
            raw_path = run_set_dir / run["raw_file"]
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines() if line]
            for record in records:
                record["config_hash"] = config_hash
            raw_path.write_text(
                "".join(json.dumps(record, separators=(",", ":")) + "\n" for record in records),
                encoding="utf-8",
            )
            run["raw_sha256"] = self._file_hash(raw_path)
        self._write_json(frozen_path, frozen)
        manifest["frozen_config_sha256"] = self._file_hash(frozen_path)
        self._write_json(manifest_path, manifest)

    def _rechain_wrong_java_performance_config(self, run_set_dir: Path) -> None:
        self._rechain_java_performance_config(
            run_set_dir,
            lambda config: config.update({"unexpected_methodology_override": True}),
        )

    def test_all_five_java_scenario_contracts_are_eligible(self):
        for scenario in self.SPECS:
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as tmp:
                run_set_dir = self._create_candidate(tmp, scenario)
                result = validate_run_set_acceptance(run_set_dir)
                self.assertTrue(result.eligible, result.reason)
                self.assertEqual(result.details["artifact_contract"], self.SPECS[scenario]["contract"])

    def test_api_key_component_dispatch_is_not_external(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "api-key-auth")
            result = validate_run_set_acceptance(run_set_dir)
            self.assertTrue(result.eligible, result.reason)
            self.assertEqual(result.details["artifact_contract"], "COMPONENT")

    def test_java_smoke_tag_is_permanently_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                self._create_candidate(tmp, "rag-completion", smoke_tag=True)
            run_set_dir = Path(tmp) / "rag-completion" / "java-formal-001"
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("status=FAILED", result.reason)

    def test_java_cleanup_failure_cannot_finalize(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                run_set_dir = self._create_candidate(
                    tmp, "rag-completion", cleanup_success=False
                )
            run_set_dir = Path(tmp) / "rag-completion" / "java-formal-001"
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("status=FAILED", result.reason)

    def test_java_performance_config_must_match_frozen_methodology(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "rag-completion")
            self._rechain_wrong_java_performance_config(run_set_dir)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("frozen methodology", result.reason)

    def test_retrieval_old_60_second_contract_remains_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            self._rechain_java_performance_config(
                run_set_dir, lambda config: config.update({"max_duration_ms": 60000})
            )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("frozen methodology", result.reason)

    def test_retrieval_old_fixed_200_warmup_contract_remains_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            def restore_old_warmup(config):
                config.pop("measurement_contracts")
                config.pop("mode_provider_path_contracts")
                config["warmup_policy"] = "discard 200"
            self._rechain_java_performance_config(run_set_dir, restore_old_warmup)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("frozen methodology", result.reason)

    def test_retrieval_old_fixtureless_contract_remains_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            def remove_fixture_contract(config):
                for field in (
                    "provider_path_contract_version", "fixture_version", "fixture_hash",
                    "fixture_manifest", "fixture_expected_documents", "fixture_expected_chunks",
                    "expected_non_empty_for_every_query", "reranker_quality_config_hash",
                    "measured_provider_path_fail_closed",
                ):
                    config.pop(field)
            self._rechain_java_performance_config(run_set_dir, remove_fixture_contract)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("frozen methodology", result.reason)

    def test_retrieval_missing_preflight_cannot_finalize(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval", finalize=False)
            manifest_path = run_set_dir / "run_set_manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest.pop("preflight_observation_file")
            manifest.pop("preflight_observation_sha256")
            self._write_json(manifest_path, manifest)
            with self.assertRaises(ValueError):
                finalize_java_run_set(run_set_dir)
            self.assertFalse(any(run_set_dir.glob("percentiles-*.json")))

    def test_retrieval_unobserved_rerank_sample_cannot_finalize(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval", finalize=False)
            manifest_path = run_set_dir / "run_set_manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            run = next(item for item in manifest["runs"] if item["mode"] == "RERANK-c1")
            raw_path = run_set_dir / run["raw_file"]
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines()]
            records[0]["execution_metadata"]["rerank_executed"] = False
            raw_path.write_text("".join(
                json.dumps(record, separators=(",", ":")) + "\n" for record in records
            ), encoding="utf-8")
            run["raw_sha256"] = self._file_hash(raw_path)
            self._write_json(manifest_path, manifest)
            with self.assertRaises(ValueError):
                finalize_java_run_set(run_set_dir)
            self.assertFalse(any(run_set_dir.glob("percentiles-*.json")))

    def test_retrieval_001_immutable_failed_artifact_remains_ineligible(self):
        project_root = Path(__file__).resolve().parents[2]
        run_set_dir = (
            project_root / "docs" / "evaluation" / "bench" / "retrieval"
            / "b-formal-retrieval-20260829-001"
        )
        expected_hashes = {
            "frozen_config.json": "5cb46bc189704ad4990a5ad7f355f905d789e9128e1ec7b7e2ff0e638571d519",
            "run_set_manifest.json": "4ada6c0b136e7c1deec29a97e128be8b4823795c723d1bea7738e84c7e026a60",
            "raw-retrieval-VECTOR-c1-run-1.jsonl": "4761908dc691021eca1a474c9825e50b8719260e13ddc0c8bbd1d1f85a4b0261",
        }
        for name, expected_hash in expected_hashes.items():
            self.assertEqual(expected_hash, self._file_hash(run_set_dir / name))
        result = validate_run_set_acceptance(run_set_dir)
        self.assertFalse(result.eligible)
        self.assertIn("status=FAILED", result.reason)

    def test_retrieval_004_immutable_repeatability_rejection_remains_ineligible(self):
        project_root = Path(__file__).resolve().parents[2]
        run_set_dir = (
            project_root / "docs" / "evaluation" / "bench" / "retrieval"
            / "b-formal-retrieval-20260829-004"
        )
        expected_hashes = {
            "frozen_config.json": "9f240bbe50a1a4f4de7628092563cc0127a1137a67860f2063f1b261fe32f18b",
            "run_set_manifest.json": "8545af84e5cad9e93d82d1af8071fb09a6871014527f3beb16162c77c690f4b6",
        }
        for name, expected_hash in expected_hashes.items():
            self.assertEqual(expected_hash, self._file_hash(run_set_dir / name))
        result = validate_run_set_acceptance(run_set_dir)
        self.assertFalse(result.eligible)
        self.assertIn("frozen methodology", result.reason)

    def test_retrieval_005_complete_history_remains_fixture_ineligible(self):
        project_root = Path(__file__).resolve().parents[2]
        run_set_dir = (
            project_root / "docs" / "evaluation" / "bench" / "retrieval"
            / "b-formal-retrieval-20260830-005"
        )
        expected_hashes = {
            "frozen_config.json": "eaeb13a369ba1377def576eb242ae1b3da15cc6f16edda669223a5118170f9c8",
            "run_set_manifest.json": "ff6d9bfe2d52e711e66fa58a9f56d96112fdcdbbe8faf3a0ec022cefc09294ab",
        }
        for name, expected_hash in expected_hashes.items():
            self.assertEqual(expected_hash, self._file_hash(run_set_dir / name))
        result = validate_run_set_acceptance(run_set_dir)
        self.assertFalse(result.eligible)
        self.assertIn("frozen methodology", result.reason)

    def test_retrieval_006_real_path_feasibility_failure_remains_immutable(self):
        project_root = Path(__file__).resolve().parents[2]
        run_set_dir = (
            project_root / "docs" / "evaluation" / "bench" / "retrieval"
            / "b-formal-retrieval-20260831-006"
        )
        expected_hashes = {
            "frozen_config.json": "9321bca032a20e9be41591e03451e9dd0bbdaf0bfe177ed5eaf0dfec040cdb5d",
            "run_set_manifest.json": "e1889bd0c41cacbfab45c7113fb8407caa756e32e8449f36e31c7606c446d432",
            "raw-retrieval-RERANK-c1-run-1.jsonl": "7a7463f9173d558aa267247796a9e253df0ae71f24fc3909d4cbaa9713bf33df",
            "cleanup_observation.json": "320cee95574c9b533f4a9e95c520ec0d8d26ae339269dd24aed14d058bb6c76e",
        }
        for name, expected_hash in expected_hashes.items():
            self.assertEqual(expected_hash, self._file_hash(run_set_dir / name))
        result = validate_run_set_acceptance(run_set_dir)
        self.assertFalse(result.eligible)
        self.assertIn("status=FAILED", result.reason)

    def test_retrieval_007_request_timeout_failure_remains_immutable(self):
        project_root = Path(__file__).resolve().parents[2]
        run_set_dir = (
            project_root / "docs" / "evaluation" / "bench" / "retrieval"
            / "b-formal-retrieval-20260901-007"
        )
        expected_hashes = {
            "frozen_config.json": "d51babf9261cb0f254dc88cb6ae098cff96622fbff3702d57997b7f2caa8e4f8",
            "run_set_manifest.json": "6f5551f8a77cdfdbe0e7822de7cfca3d7c7c2748ccb0fd67ab5d23e6db601b99",
            "preflight_observation.json": "28a19e61112edcfaaa00db28d7fcef2b7c9acae34956a8d39630ff6c74234c9f",
            "cleanup_observation.json": "e78d27c58e434ca0b650c22b19a83112fbf5c8f85f8fa40ee4fc38752a7deee6",
        }
        for name, expected_hash in expected_hashes.items():
            self.assertEqual(expected_hash, self._file_hash(run_set_dir / name))
        result = validate_run_set_acceptance(run_set_dir)
        self.assertFalse(result.eligible)
        self.assertIn("status=FAILED", result.reason)

        diagnostic = (
            project_root / "docs" / "evaluation" / "diagnostics" / "retrieval"
            / "diag-rerank-c8-provider-capacity-20260902-001"
            / "provider-only-observation.json"
        )
        self.assertEqual(
            "6b9937c261d2158556cf52882a1f0d6a8ca35fc7e77bb5a2a9627967f7f9ba2d",
            self._file_hash(diagnostic),
        )

    def test_retrieval_contract_classifier_is_static_and_fail_closed(self):
        self.assertEqual(("STANDARD_RETRIEVAL_CONTRACT", 30000),
                         (contract_for_mode("VECTOR-c1")[0],
                          contract_for_mode("VECTOR-c1")[2]["request_timeout_ms"]))
        self.assertEqual(("STANDARD_RETRIEVAL_CONTRACT", 30000),
                         (contract_for_mode("BM25-c4")[0],
                          contract_for_mode("BM25-c4")[2]["request_timeout_ms"]))
        self.assertEqual(("STANDARD_RETRIEVAL_CONTRACT", 30000),
                         (contract_for_mode("HYBRID-c8")[0],
                          contract_for_mode("HYBRID-c8")[2]["request_timeout_ms"]))
        for mode in ("RERANK-c1", "RERANK-c4", "RERANK-c8"):
            self.assertEqual(("REAL_RERANK_PROVIDER_CONTRACT", 60000),
                             (contract_for_mode(mode)[0],
                              contract_for_mode(mode)[2]["request_timeout_ms"]))
        with self.assertRaises(ValueError):
            contract_for_mode("RERANK-c2")
        with self.assertRaises(ValueError):
            contract_for_mode("unknown")

    def test_retrieval_provider_class_request_timeout_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            rerank_index = next(
                index for index, run in enumerate(manifest["runs"])
                if run["mode"] == "RERANK-c8" and run["run_id"] == "run-1"
            )
            raw_path = run_set_dir / manifest["runs"][rerank_index]["raw_file"]
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines()]
            records[0]["execution_metadata"]["request_timeout_ms"] = 30000
            self._rewrite_java_raw(run_set_dir, rerank_index, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("provider-class request timeout mismatch", result.reason)

    def test_retrieval_missing_request_timeout_evidence_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            raw_path = run_set_dir / manifest["runs"][0]["raw_file"]
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines()]
            records[0]["execution_metadata"].pop("request_timeout_ms")
            self._rewrite_java_raw(run_set_dir, 0, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("provider-class request timeout mismatch", result.reason)

    def test_retrieval_rerank_99_operations_at_900s_is_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            run_index = next(
                index for index, run in enumerate(manifest["runs"])
                if run["mode"] == "RERANK-c1" and run["run_id"] == "run-1"
            )
            raw_path = run_set_dir / manifest["runs"][run_index]["raw_file"]
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines()][:99]
            for index, record in enumerate(records):
                record["sample_index"] = index
                record["run_relative_time"] = 900000.0 * index / 98
            self._rewrite_java_raw(run_set_dir, run_index, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("measured operations below contract", result.reason)

    def test_retrieval_rerank_100_operations_at_599s_is_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            run_index = next(
                index for index, run in enumerate(manifest["runs"])
                if run["mode"] == "RERANK-c1" and run["run_id"] == "run-1"
            )
            raw_path = run_set_dir / manifest["runs"][run_index]["raw_file"]
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines()]
            for index, record in enumerate(records):
                record["latency_ms"] = 5990.0
                record["run_relative_time"] = 5990.0 * index
            self._rewrite_java_raw(run_set_dir, run_index, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("measured operation duration below contract", result.reason)

    def test_retrieval_rerank_100_operations_at_899s_is_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            run_index = next(
                index for index, run in enumerate(manifest["runs"])
                if run["mode"] == "RERANK-c1" and run["run_id"] == "run-1"
            )
            raw_path = run_set_dir / manifest["runs"][run_index]["raw_file"]
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines()]
            for index, record in enumerate(records):
                record["run_relative_time"] = 893000.0 * index / 99
            self._rewrite_java_raw(run_set_dir, run_index, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertTrue(result.eligible, result.reason)

    def test_retrieval_run_observation_cannot_cross_contract_classes(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            self._mutate_java_observation(
                run_set_dir,
                lambda observation: observation.update({
                    "contract_class": "REAL_RERANK_PROVIDER_CONTRACT",
                    "provider_path_class": "REAL_RERANK_PROVIDER_PATH",
                }),
            )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("contract classification mismatch", result.reason)

    def test_retrieval_percentile_contract_evidence_is_recomputed_from_raw(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            manifest_path = run_set_dir / "run_set_manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            run = manifest["runs"][0]
            percentile_path = run_set_dir / run["percentile_file"]
            percentile = json.loads(percentile_path.read_text(encoding="utf-8"))
            percentile["measured_operations"] += 1
            self._write_json(percentile_path, percentile)
            run["percentile_sha256"] = self._file_hash(percentile_path)
            self._write_json(manifest_path, manifest)

            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn(
                "percentile measurement contract measured_operations mismatch",
                result.reason,
            )

    def test_retrieval_old_config_raw_cannot_link_to_amended_candidate(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            raw_path = run_set_dir / manifest["runs"][0]["raw_file"]
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines() if line]
            records[0]["config_hash"] = (
                "0e81204dcae7f46edb62be91e2ba9f9fd55374f1ab6f5b6443ecb50c8b2881ad"
            )
            self._rewrite_java_raw(run_set_dir, 0, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("raw config_hash linkage mismatch", result.reason)

    def test_retrieval_config_hash_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            manifest_path = run_set_dir / "run_set_manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            frozen_path = run_set_dir / manifest["frozen_config_file"]
            frozen = json.loads(frozen_path.read_text(encoding="utf-8"))
            frozen["performance_config"]["max_duration_ms"] = 60000
            self._write_json(frozen_path, frozen)
            manifest["frozen_config_sha256"] = self._file_hash(frozen_path)
            self._write_json(manifest_path, manifest)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("performance config canonical hash mismatch", result.reason)

    def test_retrieval_duration_above_60s_below_180s_is_eligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            raw_path = run_set_dir / manifest["runs"][0]["raw_file"]
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines() if line]
            for index, record in enumerate(records):
                record["run_relative_time"] = 120000.0 * (index + 1) / len(records)
            self._rewrite_java_raw(run_set_dir, 0, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertTrue(result.eligible, result.reason)

    def test_component_duration_is_derived_from_operation_end(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            raw_path = run_set_dir / manifest["runs"][0]["raw_file"]
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines() if line]
            for index, record in enumerate(records):
                record["run_relative_time"] = 4999.0 * (index + 1) / len(records)
            self._rewrite_java_raw(run_set_dir, 0, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertTrue(result.eligible, result.reason)

    def test_retrieval_duration_above_180s_is_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            raw_path = run_set_dir / manifest["runs"][0]["raw_file"]
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines() if line]
            for index, record in enumerate(records):
                record["run_relative_time"] = 180001.0 * (index + 1) / len(records)
            self._rewrite_java_raw(run_set_dir, 0, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("measured wall duration exceeds contract ceiling", result.reason)

    def test_component_warmup_observation_mismatch_is_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "api-key-auth")
            self._mutate_java_observation(
                run_set_dir, lambda observation: observation.update({"warmup_completed": 199})
            )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("warmup observation mismatch", result.reason)

    def test_retrieval_warmup_completed_operations_below_minimum_is_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            self._mutate_java_observation(
                run_set_dir,
                lambda observation: observation.update({"warmup_completed_operations": 199}),
            )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("completed operations below contract", result.reason)

    def test_retrieval_warmup_operation_duration_below_minimum_is_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            self._mutate_java_observation(
                run_set_dir,
                lambda observation: observation.update({"warmup_operation_duration_ms": 4999}),
            )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("operation duration below contract", result.reason)

    def test_retrieval_measurement_must_start_after_warmup(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            self._mutate_java_observation(
                run_set_dir,
                lambda observation: observation.update({"measurement_started_after_warmup": False}),
            )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("measured window did not start after warmup", result.reason)

    def test_retrieval_legacy_warmup_truth_field_is_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "retrieval")
            self._mutate_java_observation(
                run_set_dir,
                lambda observation: observation.update({"warmup_completed": 200}),
            )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("redundant observation field", result.reason)

    def test_redundant_java_observation_result_field_is_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "rag-completion")
            self._mutate_java_observation(
                run_set_dir, lambda observation: observation.update({"measured_sample_count": 30})
            )
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("redundant observation field", result.reason)

    def test_component_insufficient_sample_count_is_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "api-key-auth")
            manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            raw_path = run_set_dir / manifest["runs"][0]["raw_file"]
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines() if line][1:]
            for index, record in enumerate(records):
                record["sample_index"] = index
            self._rewrite_java_raw(run_set_dir, 0, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("insufficient component sample count", result.reason)

    def test_component_duration_derived_from_raw_is_ineligible_below_five_seconds(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "api-key-auth")
            manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            raw_path = run_set_dir / manifest["runs"][0]["raw_file"]
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines() if line]
            for index, record in enumerate(records):
                record["run_relative_time"] = float(index + 1)
            self._rewrite_java_raw(run_set_dir, 0, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("measured duration below 5s", result.reason)

    def test_b_class_exact_n_mismatch_is_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "rag-completion")
            manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            raw_path = run_set_dir / manifest["runs"][0]["raw_file"]
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines() if line][:-1]
            self._rewrite_java_raw(run_set_dir, 0, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("exact-N sample count mismatch", result.reason)

    def test_java_raw_identity_linkages_are_fail_closed(self):
        mutations = {
            "scenario": "wrong", "run_set_id": "wrong", "run_id": "run-2",
            "config_hash": "f" * 64, "environment_hash": "e" * 64,
        }
        for field, value in mutations.items():
            with self.subTest(field=field), tempfile.TemporaryDirectory() as tmp:
                run_set_dir = self._create_candidate(tmp, "rag-completion")
                manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
                raw_path = run_set_dir / manifest["runs"][0]["raw_file"]
                records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines() if line]
                records[0][field] = value
                self._rewrite_java_raw(run_set_dir, 0, records)
                result = validate_run_set_acceptance(run_set_dir)
                self.assertFalse(result.eligible)
                self.assertIn(f"raw {field} linkage mismatch", result.reason)

    def test_java_raw_mode_linkage_is_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "rag-completion")
            manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            raw_path = run_set_dir / manifest["runs"][0]["raw_file"]
            records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines() if line]
            records[0]["execution_metadata"]["evidence_mode"] = "wrong"
            self._rewrite_java_raw(run_set_dir, 0, records)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("raw evidence_mode mismatch", result.reason)

    def test_java_incomplete_and_duplicate_run_links_are_ineligible(self):
        for duplicate in (False, True):
            with self.subTest(duplicate=duplicate), tempfile.TemporaryDirectory() as tmp:
                run_set_dir = self._create_candidate(tmp, "rag-completion")
                manifest_path = run_set_dir / "run_set_manifest.json"
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                if duplicate:
                    manifest["runs"][-1] = dict(manifest["runs"][0])
                else:
                    manifest["runs"].pop()
                self._write_json(manifest_path, manifest)
                result = validate_run_set_acceptance(run_set_dir)
                self.assertFalse(result.eligible)
                expected = "invalid/duplicate" if duplicate else "run-link cardinality"
                self.assertIn(expected, result.reason)

    def test_java_malformed_raw_is_ineligible(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "rag-completion")
            manifest_path = run_set_dir / "run_set_manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            run = manifest["runs"][0]
            raw_path = run_set_dir / run["raw_file"]
            raw_path.write_text("{malformed\n", encoding="utf-8")
            run["raw_sha256"] = self._file_hash(raw_path)
            self._write_json(manifest_path, manifest)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("Java artifact failure", result.reason)

    def test_java_missing_and_corrupt_percentile_links_are_ineligible(self):
        for corrupt in (False, True):
            with self.subTest(corrupt=corrupt), tempfile.TemporaryDirectory() as tmp:
                run_set_dir = self._create_candidate(tmp, "rag-completion")
                manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
                percentile_path = run_set_dir / manifest["runs"][0]["percentile_file"]
                if corrupt:
                    percentile_path.write_text("{}", encoding="utf-8")
                else:
                    percentile_path.unlink()
                result = validate_run_set_acceptance(run_set_dir)
                self.assertFalse(result.eligible)
                self.assertIn("percentile artifact", result.reason)

    def test_b_class_provider_concurrency_and_timeout_linkages_are_ineligible(self):
        mutations = {
            "provider_mode": "real",
            "concurrency": 2,
            "request_timeout_ms": 1,
        }
        for field, value in mutations.items():
            with self.subTest(field=field), tempfile.TemporaryDirectory() as tmp:
                run_set_dir = self._create_candidate(tmp, "rag-completion")
                manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
                raw_path = run_set_dir / manifest["runs"][0]["raw_file"]
                records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines() if line]
                if field == "request_timeout_ms":
                    records[0]["execution_metadata"][field] = value
                else:
                    records[0][field] = value
                self._rewrite_java_raw(run_set_dir, 0, records)
                result = validate_run_set_acceptance(run_set_dir)
                self.assertFalse(result.eligible)
                self.assertIn("linkage mismatch", result.reason)

    def test_document_runtime_provenance_missing_and_inconsistent_are_ineligible(self):
        for mutation in (
            "missing", "config_only", "wrong_ids", "not_ready", "missing_final_snapshot",
            "missing_probe_source", "index_task_not_succeeded", "bad_chunk_digest",
            "missing_retrieval_task", "bad_object_stat",
        ):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as tmp:
                run_set_dir = self._create_candidate(tmp, "document-processing")
                manifest_path = run_set_dir / "run_set_manifest.json"
                manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                provenance_path = run_set_dir / manifest["runtime_provenance_file"]
                provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
                if mutation == "missing":
                    provenance_path.unlink()
                else:
                    if mutation == "config_only":
                        provenance["pre_measurement"] = {"jdbc": {}, "minio": {}, "rabbitmq": {}}
                    elif mutation == "wrong_ids":
                        provenance["measured_document_ids"] = [999999]
                    elif mutation == "not_ready":
                        provenance["documents"][0]["retrieval_task_status"] = "PENDING"
                    elif mutation == "missing_final_snapshot":
                        provenance.pop("final_snapshot")
                    elif mutation == "missing_probe_source":
                        provenance["pre_measurement"]["jdbc"].pop("source")
                    elif mutation == "index_task_not_succeeded":
                        provenance["documents"][0]["document_index_task_status"] = "FAILED"
                    elif mutation == "bad_chunk_digest":
                        provenance["documents"][0]["chunk_digest"] = "not-a-digest"
                    elif mutation == "missing_retrieval_task":
                        provenance["documents"][0]["retrieval_task_id"] = None
                    elif mutation == "bad_object_stat":
                        provenance["documents"][0]["minio_object_size"] = 0
                    self._write_json(provenance_path, provenance)
                    manifest["runtime_provenance_sha256"] = self._file_hash(provenance_path)
                    self._write_json(manifest_path, manifest)
                result = validate_run_set_acceptance(run_set_dir)
                self.assertFalse(result.eligible)
                self.assertIn("runtime provenance", result.reason)

    def test_java_nonfinite_negative_or_contradictory_success_raw_is_ineligible(self):
        for field, value in (
            ("latency_ms", float("nan")), ("latency_ms", -1.0),
            ("run_relative_time", float("inf")), ("status_code", 500), ("error", "unexpected"),
        ):
            with self.subTest(field=field, value=value), tempfile.TemporaryDirectory() as tmp:
                run_set_dir = self._create_candidate(tmp, "rag-completion")
                manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
                raw_path = run_set_dir / manifest["runs"][0]["raw_file"]
                records = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines() if line]
                records[0][field] = value
                self._rewrite_java_raw(run_set_dir, 0, records)
                result = validate_run_set_acceptance(run_set_dir)
                self.assertFalse(result.eligible)

    def test_document_cannot_finalize_without_linked_runtime_provenance(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "document-processing", finalize=False)
            manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            (run_set_dir / manifest["runtime_provenance_file"]).unlink()
            with self.assertRaises(ValueError):
                finalize_java_run_set(run_set_dir)
            failed = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(failed["status"], "FAILED")

    def test_java_finalizer_fail_if_percentile_exists(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "rag-completion", finalize=False)
            manifest = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            raw_name = manifest["runs"][0]["raw_file"]
            percentile_name = raw_name.replace("raw-", "percentiles-", 1).replace(".jsonl", ".json")
            self._write_json(run_set_dir / percentile_name, {"preexisting": True})
            with self.assertRaises(ValueError):
                finalize_java_run_set(run_set_dir)
            failed = json.loads((run_set_dir / "run_set_manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(failed["status"], "FAILED")

    def test_mixed_external_java_markers_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_set_dir = self._create_candidate(tmp, "rag-completion")
            manifest_path = run_set_dir / "run_set_manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["measurement_status"] = "COMPLETE"
            self._write_json(manifest_path, manifest)
            result = validate_run_set_acceptance(run_set_dir)
            self.assertFalse(result.eligible)
            self.assertIn("mixed", result.reason)


if __name__ == "__main__":
    unittest.main(verbosity=2)
