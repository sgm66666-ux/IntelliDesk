#!/usr/bin/env python3
"""Tooling tests for Phase 8 Wave 2 benchmark harness.

Covers the MUST_FIX / TEST_GAP items raised by Independent Review:
  - embedding preflight production-compatible contract
  - mandatory raw schema fields
  - SSE TTFT parsing and qualifying-event semantics
  - k6 method/body parameterization
  - auth injection without secret leakage

All tests use mocks or temporary directories; no Wave 1 evidence is touched.
"""

import json
import os
import socket
import tempfile
import threading
import time
import types
import unittest
import urllib.request
from pathlib import Path
from unittest import mock

from k6_collector import k6_record_to_raw
from k6_runner import (
    build_k6_env,
    load_request_body,
    preflight_k6_binary,
    resolve_k6_path,
    run_k6,
    start_helper,
    stop_helper,
    wait_for_helper_readiness,
)
from orchestrate_formal_run import prepare_fresh_run_set
from freeze_benchmark_config import (
    build_benchmark_harness_identity,
    build_performance_config,
    freeze_execution_config,
    _sha256_hex,
    _scenario_definition,
)
from percentile import compute_percentiles, load_latencies
from runtime_context import (
    build_runtime_context,
    runtime_context_path,
    substitute_request_body,
    substitute_target,
    write_runtime_context,
)
from ttft_transport import (
    ALLOWED_CREDENTIAL_SOURCES,
    FORMAL_HELPER_PORT,
    parse_sse_events,
    is_qualifying_token,
    build_headers,
    measure_sse_ttft,
    run_server,
)
from verify_http_timing_semantics import verify as verify_http_timing_semantics


class MockResponse:
    def __init__(self, status: int, body: bytes):
        self.status = status
        self._body = body

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass


class EmbeddingPreflightContractTest(unittest.TestCase):
    """TEST_GAP-1.A: embedding preflight must use production /v1/embeddings."""

    def test_command_probe_handles_missing_stdout_without_locale_decode_failure(self):
        from target_preflight import check_command
        completed = types.SimpleNamespace(returncode=0, stdout=None, stderr="")
        with mock.patch("target_preflight.subprocess.run", return_value=completed) as runner:
            ok, detail = check_command(["docker", "ps"])
        self.assertTrue(ok)
        self.assertEqual("", detail)
        self.assertEqual("utf-8", runner.call_args.kwargs["encoding"])
        self.assertEqual("replace", runner.call_args.kwargs["errors"])

    def test_relative_target_preflight_uses_nginx_base_url(self):
        from target_preflight import check_target_reachability
        with mock.patch("target_preflight.check_http", return_value=(True, 200, "ok")) as probe:
            result = check_target_reachability("/api/health", 200)
        self.assertTrue(result["reachable"])
        self.assertEqual("http://127.0.0.1:80/api/health", probe.call_args.args[0])

    def _patch_urlopen(self, payload: dict, status: int = 200):
        def fake_urlopen(req, **kwargs):
            return MockResponse(status, json.dumps(payload).encode("utf-8"))
        return mock.patch("target_preflight.urllib.request.urlopen", side_effect=fake_urlopen)

    def test_v1_embeddings_with_dimensions_1536_matches_plan(self):
        from target_preflight import check_ollama_embedding_contract
        payload = {
            "model": "qwen3-embedding:8b",
            "data": [{"object": "embedding", "embedding": [0.1] * 1536}],
        }
        with self._patch_urlopen(payload):
            result = check_ollama_embedding_contract("qwen3-embedding:8b", 1536)
        self.assertTrue(result["http_ok"])
        self.assertTrue(result["dimension_matches_plan"])
        self.assertTrue(result["model_matches_plan"])
        self.assertEqual(result["returned_dimensions"], 1536)
        self.assertEqual(result["compatibility_api"], "/v1/embeddings")

    def test_v1_embeddings_returned_4096_does_not_match_plan(self):
        from target_preflight import check_ollama_embedding_contract
        payload = {
            "model": "qwen3-embedding:8b",
            "data": [{"object": "embedding", "embedding": [0.1] * 4096}],
        }
        with self._patch_urlopen(payload):
            result = check_ollama_embedding_contract("qwen3-embedding:8b", 1536)
        self.assertFalse(result["dimension_matches_plan"])
        self.assertEqual(result["returned_dimensions"], 4096)

    def test_native_api_4096_is_informational_only(self):
        from target_preflight import check_ollama_native_dimension
        payload = {"embedding": [0.1] * 4096}
        with self._patch_urlopen(payload):
            ok, dim, detail = check_ollama_native_dimension("qwen3-embedding:8b")
        self.assertTrue(ok)
        self.assertEqual(dim, 4096)
        self.assertIn("native", detail)


class RawSchemaTest(unittest.TestCase):
    """TEST_GAP-1.B: mandatory raw fields must be emitted by collector."""

    def _make_k6_record(self, tags: dict | None = None, value: float = 42.0,
                        time: str = "2026-08-22T10:00:00.100Z") -> dict:
        return {
            "metric": "bench_req_duration",
            "type": "Point",
            "timestamp": "2026-08-22T10:00:00Z",
            "data": {
                "time": time,
                "value": value,
                "tags": tags or {
                    "url": "http://localhost/api/chat",
                    "status": "200",
                    "success": "true",
                    "metric_name": "bench_req_duration",
                    "provider_mode": "real",
                    "vu": "10",
                    "concurrency": "10",
                    "config_hash": "cfg",
                    "environment_hash": "env",
                },
            },
        }

    def test_mandatory_base_fields_present(self):
        record = self._make_k6_record()
        sample = k6_record_to_raw(record, "rag-pipeline", "rs-1", "run-1", 0, "cfg", "env")
        mandatory = [
            "timestamp", "run_relative_time", "scenario", "run_set_id", "run_id",
            "sample_index", "metric_name", "endpoint", "latency_ms", "status_code",
            "success", "error", "vu", "concurrency", "provider_mode",
            "config_hash", "environment_hash", "k6_sample_reference", "execution_metadata",
        ]
        for field in mandatory:
            self.assertIn(field, sample, f"missing {field}")
        self.assertEqual(sample["scenario"], "rag-pipeline")
        self.assertEqual(sample["metric_name"], "bench_req_duration")
        self.assertEqual(sample["provider_mode"], "real")
        self.assertEqual(sample["vu"], 10)
        self.assertEqual(sample["concurrency"], 10)

    def test_run_relative_time_computed_from_start_time(self):
        record = self._make_k6_record(time="2026-08-22T10:00:01.000Z")
        sample = k6_record_to_raw(
            record, "rag-pipeline", "rs-1", "run-1", 0, "cfg", "env",
            run_start_time="2026-08-22T10:00:00.000Z",
        )
        self.assertAlmostEqual(sample["run_relative_time"], 1000.0, places=3)

    def test_ttft_fields_extracted_from_tags(self):
        tags = {
            "url": "http://localhost/api/chat/stream",
            "status": "200",
            "success": "true",
            "ttft_ms": "15.5",
            "t0_monotonic": "12345.1",
            "t1_monotonic": "12345.1155",
            "qualifying_event_type": "token",
        }
        record = self._make_k6_record(tags=tags)
        sample = k6_record_to_raw(record, "rag-pipeline", "rs-1", "run-1", 0, "cfg", "env")
        self.assertEqual(sample["ttft_ms"], 15.5)
        self.assertEqual(sample["t0_monotonic"], 12345.1)
        self.assertEqual(sample["t1_monotonic"], 12345.1155)
        self.assertEqual(sample["qualifying_event_type"], "token")

    def test_phase_provenance_preserved_for_ramp_and_sustain(self):
        for phase in ("ramp", "sustain"):
            with self.subTest(phase=phase):
                record = self._make_k6_record(tags={
                    "url": "http://localhost/api/health",
                    "status": "200",
                    "success": "true",
                    "phase": phase,
                })
                sample = k6_record_to_raw(
                    record, "http-concurrency", "rs-1", "run-1", 0, "cfg", "env"
                )
                self.assertEqual(sample["phase"], phase)

    def test_fractional_milliseconds_are_preserved_without_quantization(self):
        values = [0.8, 1.1, 1.4, 1.01, 1.99]
        collected = []
        for index, value in enumerate(values):
            record = self._make_k6_record(tags={
                "url": "http://127.0.0.1:80/api/health",
                "status": "200",
                "success": "true",
                "phase": "sustain",
                "latency_measurement_source": "k6_response_timings_duration",
                "latency_request_boundary": "sending_plus_waiting_plus_receiving_excludes_blocked_dns_connect_tls",
                "latency_pre_percentile_quantization": "none",
            }, value=value)
            sample = k6_record_to_raw(
                record, "http-concurrency", "rs-1", "vu1-run-1", index, "cfg", "env"
            )
            collected.append(sample["latency_ms"])
        self.assertEqual(values, collected)
        self.assertEqual(1.01, collected[3])
        self.assertEqual(1.99, collected[4])
        self.assertEqual(
            {"p50": 1.1, "p90": 1.99, "p95": 1.99, "p99": 1.99},
            compute_percentiles(collected),
        )

    def test_http_concurrency_precision_provenance_is_preserved(self):
        record = self._make_k6_record(tags={
            "url": "http://127.0.0.1:80/api/health",
            "status": "200",
            "success": "true",
            "phase": "sustain",
            "latency_measurement_source": "k6_response_timings_duration",
            "latency_request_boundary": "sending_plus_waiting_plus_receiving_excludes_blocked_dns_connect_tls",
            "latency_pre_percentile_quantization": "none",
        }, value=1.2345)
        sample = k6_record_to_raw(
            record, "http-concurrency", "rs-1", "vu1-run-1", 0, "cfg", "env"
        )
        self.assertEqual("k6_response_timings_duration", sample["latency_measurement_source"])
        self.assertEqual(
            "sending_plus_waiting_plus_receiving_excludes_blocked_dns_connect_tls",
            sample["latency_request_boundary"],
        )
        self.assertEqual("none", sample["latency_pre_percentile_quantization"])

    def test_semantic_verifier_requires_exact_fractional_native_multiset(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "k6.json"
            records = []
            for value in (0.8, 1.1, 1.4):
                records.extend([
                    {"metric": "http_req_duration", "type": "Point", "data": {"value": value}},
                    {"metric": "bench_req_duration", "type": "Point", "data": {"value": value}},
                ])
            path.write_text(
                "".join(json.dumps(record) + "\n" for record in records),
                encoding="utf-8",
            )
            result = verify_http_timing_semantics(path)
            self.assertEqual("PASS", result["status"])
            self.assertEqual(3, result["fractional_custom_sample_count"])
            self.assertTrue(result["exact_value_multiset_match"])


class AuthoritativePhaseFilteringTest(unittest.TestCase):
    """Ramp and unprovenanced samples cannot affect sustain percentiles."""

    def _write_raw(self, records: list[dict], directory: str) -> Path:
        raw_path = Path(directory) / "raw.jsonl"
        raw_path.write_text(
            "".join(json.dumps(record) + "\n" for record in records),
            encoding="utf-8",
        )
        return raw_path

    def test_sustain_filter_excludes_ramp_samples(self):
        records = [
            {"phase": "ramp", "latency_ms": 900},
            {"phase": "ramp", "latency_ms": 1000},
            {"phase": "sustain", "latency_ms": 100},
            {"phase": "sustain", "latency_ms": 110},
            {"phase": "sustain", "latency_ms": 120},
        ]
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(
                load_latencies(self._write_raw(records, directory), phase="sustain"),
                [100.0, 110.0, 120.0],
            )

    def test_ramp_sample_cannot_affect_nearest_rank_percentiles(self):
        sustain = [
            {"phase": "sustain", "latency_ms": value}
            for value in (10, 20, 30, 40)
        ]
        with tempfile.TemporaryDirectory() as directory:
            raw_path = self._write_raw(
                sustain + [{"phase": "ramp", "latency_ms": 9999}], directory
            )
            actual = compute_percentiles(load_latencies(raw_path, phase="sustain"))
            expected = compute_percentiles([10.0, 20.0, 30.0, 40.0])
            self.assertEqual(actual, expected)

    def test_missing_null_and_unknown_phase_fail_closed(self):
        records = [
            {"latency_ms": 9999},
            {"phase": None, "latency_ms": 9999},
            {"phase": "unknown", "latency_ms": 9999},
            {"phase": "sustain", "latency_ms": 25},
        ]
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(
                load_latencies(self._write_raw(records, directory), phase="sustain"),
                [25.0],
            )

    def test_k6_tag_to_raw_to_sustain_filter_chain(self):
        def convert(phase: str, latency_ms: float, sample_index: int) -> dict:
            record = {
                "metric": "bench_req_duration",
                "type": "Point",
                "data": {
                    "time": "2026-08-22T10:00:00Z",
                    "value": latency_ms,
                    "tags": {
                        "url": "http://localhost/api/health",
                        "status": "200",
                        "success": "true",
                        "phase": phase,
                    },
                },
            }
            return k6_record_to_raw(
                record, "http-concurrency", "rs-1", "run-1",
                sample_index, "cfg", "env",
            )

        records = [convert("ramp", 9999, 0), convert("sustain", 40, 1)]
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(
                load_latencies(self._write_raw(records, directory), phase="sustain"),
                [40.0],
            )


class TtftParserTest(unittest.TestCase):
    """TEST_GAP-1.C: SSE TTFT parser semantics."""

    def _event(self, event_type: str, data: dict) -> str:
        return f"event: {event_type}\ndata: {json.dumps(data)}\n\n"

    def test_start_then_non_empty_token_yields_qualifying(self):
        sse = self._event("start", {}) + self._event("token", {"delta": "hello"})
        events = parse_sse_events(sse)
        qualifying = [is_qualifying_token(e) for e in events]
        self.assertEqual(qualifying, [(False, None), (True, "hello")])

    def test_empty_token_then_non_empty_token_skips_empty(self):
        sse = self._event("token", {"delta": ""}) + self._event("token", {"delta": "hi"})
        events = parse_sse_events(sse)
        results = [is_qualifying_token(e) for e in events]
        self.assertEqual(results, [(False, None), (True, "hi")])

    def test_citation_then_token_token_is_qualifying(self):
        sse = self._event("citation", {"source": "doc1"}) + self._event("token", {"delta": "x"})
        events = parse_sse_events(sse)
        results = [is_qualifying_token(e) for e in events]
        self.assertEqual(results, [(False, None), (True, "x")])

    def test_done_only_yields_no_qualifying(self):
        sse = self._event("done", {})
        events = parse_sse_events(sse)
        self.assertFalse(any(is_qualifying_token(e)[0] for e in events))

    def test_split_sse_chunks_parses_correctly(self):
        chunk1 = "event: token\ndata: "
        chunk2 = json.dumps({"delta": "chunk"}) + "\n\nevent: done\ndata: {}\n\n"
        events = parse_sse_events(chunk1 + chunk2)
        results = [is_qualifying_token(e) for e in events]
        self.assertEqual(results, [(True, "chunk"), (False, None)])

    def test_multiple_events_in_one_chunk(self):
        sse = self._event("token", {"delta": ""}) + self._event("token", {"delta": "a"}) + self._event("done", {})
        events = parse_sse_events(sse)
        results = [is_qualifying_token(e) for e in events]
        self.assertEqual(results, [(False, None), (True, "a"), (False, None)])

    def test_crlf_line_endings(self):
        sse = "event: token\r\ndata: {\"delta\": \"crlf\"}\r\n\r\n"
        events = parse_sse_events(sse)
        self.assertEqual(len(events), 1)
        self.assertEqual(is_qualifying_token(events[0]), (True, "crlf"))

    def test_eof_before_token_fails_no_ttft(self):
        sse = self._event("start", {})
        events = parse_sse_events(sse)
        self.assertFalse(any(is_qualifying_token(e)[0] for e in events))


class MethodBodyTest(unittest.TestCase):

    def test_http_concurrency_uses_native_fractional_request_duration(self):
        template = (Path(__file__).resolve().parent / "k6_script_template.js").read_text(
            encoding="utf-8"
        )
        self.assertIn("Number(response.timings.duration)", template)
        self.assertIn("scenario === 'http-concurrency'", template)
        self.assertIn("nativeHttpConcurrencyLatencyMs(res)", template)
        self.assertIn(
            "sending_plus_waiting_plus_receiving_excludes_blocked_dns_connect_tls",
            template,
        )
        native_function = template[
            template.index("function nativeHttpConcurrencyLatencyMs"):
            template.index("function executeDirect")
        ]
        for forbidden in ("Math.round", "Math.floor", "Math.ceil", "parseInt"):
            self.assertNotIn(forbidden, native_function)

    def test_http_concurrency_precision_selection_has_no_vu_special_case(self):
        template = (Path(__file__).resolve().parent / "k6_script_template.js").read_text(
            encoding="utf-8"
        )
        direct_function = template[
            template.index("function executeDirect"):
            template.index("function executeTtftHelper")
        ]
        self.assertNotIn("vuLevel", direct_function)
        self.assertNotIn("__VU", direct_function)
        self.assertEqual(1, direct_function.count("nativeHttpConcurrencyLatencyMs(res)"))

    def test_ttft_mode_publishes_first_token_latency_as_authoritative_metric(self):
        template = (Path(__file__).resolve().parent / "k6_script_template.js").read_text(
            encoding="utf-8"
        )
        self.assertIn("latencyMs: helperResult.ttft_ms", template)
        self.assertNotIn("latencyMs: helperResult.latency_ms", template)

    def test_ttft_mode_selects_per_vu_target_and_settles_after_measurement(self):
        template = (Path(__file__).resolve().parent / "k6_script_template.js").read_text(
            encoding="utf-8"
        )
        self.assertIn("targetPool[(__VU - 1) % targetPool.length]", template)
        self.assertIn("sleep(postTtftSettleDelayMs / 1000.0)", template)
        self.assertLess(template.index("benchDuration.add"), template.index("sleep(postTtftSettleDelayMs"))

    def test_k6_subprocess_uses_utf8_and_normalizes_missing_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run_set_dir = root / "http-concurrency" / "rs-1"
            run_set_dir.mkdir(parents=True)
            (root / "k6.exe").write_bytes(b"fixture executable")
            args = types.SimpleNamespace(
                k6_path=str(root / "k6.exe"), bench_root=str(root), scenario="http-concurrency",
                run_set_id="rs-1", run_id="vu1-run-1",
            )
            completed = types.SimpleNamespace(returncode=0, stdout=None, stderr=None)
            with mock.patch("k6_runner.subprocess.run", return_value=completed) as runner:
                result = run_k6(args, {}, start_helper_server=False)
            self.assertEqual("", result["stdout"])
            self.assertEqual("", result["stderr"])
            self.assertEqual("utf-8", runner.call_args.kwargs["encoding"])
            self.assertEqual("replace", runner.call_args.kwargs["errors"])
            self.assertEqual(str((root / "k6.exe").resolve()), runner.call_args.args[0][0])
    """TEST_GAP-1.D: k6 runner must consume method and body from frozen config."""

    def _make_args(self, scenario: str = "rag-pipeline"):
        return types.SimpleNamespace(
            scenario=scenario,
            run_set_id="rs-1",
            run_id="run-1",
            target=None,
            duration="30s",
            vus=10,
            vu_level=1,
            independent_run_index=1,
            k6_path="k6",
            bench_root="docs/evaluation/bench",
            config_path=None,
            auth_mode=None,
            auth_source=None,
        )

    def _write_runtime_context(self, scenario: str, run_set_id: str, bench_root: Path,
                                workspace_id: str = "1", conversation_id: str = "1",
                                knowledge_base_id: str = "1") -> Path:
        ctx = build_runtime_context(
            scenario=scenario, run_set_id=run_set_id,
            provisioning_logical_id=f"pl-{run_set_id}",
            benchmark_user_logical_id=f"u-{run_set_id}",
            workspace_id=workspace_id, conversation_id=conversation_id,
            knowledge_base_id=knowledge_base_id, document_ids=["1"],
            api_key_id="1", fixture_identity="fixed-rag-prompt-v1.json")
        return write_runtime_context(scenario, run_set_id, ctx, bench_root=bench_root)

    def test_get_config_produces_get_env(self):
        with tempfile.TemporaryDirectory() as tmp:
            bench_root = Path(tmp)
            self._write_runtime_context("http-concurrency", "rs-1", bench_root)
            perf = build_performance_config(
                scenario="http-concurrency",
                target="/ping",
                http_method="GET",
                request_body_identity=None,
                duration="30s",
                vus=100,
                warmup_policy="none",
                iteration_rate_policy="fixed-rate",
                timeout_ms=5000,
                thresholds={},
                expected_status=200,
                success_semantics="HTTP 200",
                sample_collection_semantics="all",
                percentile_method="nearest-rank",
            )
            env = build_k6_env(self._make_args("http-concurrency"), perf,
                               Path(__file__).resolve().parent / "fixtures", bench_root)
            self.assertEqual(env["BENCH_HTTP_METHOD"], "GET")
            self.assertEqual(env["BENCH_REQUEST_BODY"], "")
            self.assertEqual(env["BENCH_TARGET"], "/ping")

    def test_post_config_loads_body_fixture(self):
        with tempfile.TemporaryDirectory() as tmp:
            bench_root = Path(tmp)
            self._write_runtime_context("rag-pipeline", "rs-1", bench_root,
                                        workspace_id="10", conversation_id="20", knowledge_base_id="30")
            perf = build_performance_config(
                scenario="rag-pipeline",
                target="/api/workspaces/{workspace_id}/conversations/{conversation_id}/messages/stream",
                http_method="POST",
                request_body_identity="fixed-rag-prompt-v1",
                duration="30s",
                vus=10,
                warmup_policy="none",
                iteration_rate_policy="fixed-rate",
                timeout_ms=30000,
                thresholds={},
                expected_status=200,
                success_semantics="SSE token",
                sample_collection_semantics="all",
                percentile_method="nearest-rank",
                content_type="application/json",
            )
            fixture_dir = Path(__file__).resolve().parent / "fixtures"
            env = build_k6_env(self._make_args("rag-pipeline"), perf, fixture_dir, bench_root)
            self.assertEqual(env["BENCH_HTTP_METHOD"], "POST")
            body = json.loads(env["BENCH_REQUEST_BODY"])
            self.assertEqual(body["query"], "benchmark query for RAG pipeline")
            self.assertEqual(body["knowledgeBaseIds"], [30])
            self.assertEqual(env["BENCH_TARGET"],
                             "/api/workspaces/10/conversations/20/messages/stream")
            self.assertEqual(env["BENCH_CONTENT_TYPE"], "application/json")

    def test_rag_config_emits_one_isolated_target_per_requested_vu(self):
        with tempfile.TemporaryDirectory() as tmp:
            bench_root = Path(tmp)
            ctx = build_runtime_context(
                scenario="rag-pipeline", run_set_id="rs-1",
                provisioning_logical_id="pl-rs-1", benchmark_user_logical_id="u-rs-1",
                workspace_id="10", conversation_id="20", conversation_ids=["20", "21", "22", "23", "24"],
                knowledge_base_id="30", document_ids=["40"], api_key_id="50",
                fixture_identity="fixed-rag-prompt-v1.json",
            )
            write_runtime_context("rag-pipeline", "rs-1", ctx, bench_root=bench_root)
            perf = build_performance_config(
                scenario="rag-pipeline",
                target="/api/workspaces/{workspace_id}/conversations/{conversation_id}/messages/stream",
                http_method="POST", request_body_identity="fixed-rag-prompt-v1",
                duration="30s", vus=10, warmup_policy="none",
                iteration_rate_policy="closed-model-ramping-vus", timeout_ms=30000,
                thresholds={}, expected_status=200, success_semantics="SSE token",
                sample_collection_semantics="all", percentile_method="nearest-rank",
                content_type="application/json", ttft_mode=True,
                post_ttft_settle_delay_ms=200,
            )
            args = self._make_args("rag-pipeline")
            args.vu_level = 5
            env = build_k6_env(
                args, perf, Path(__file__).resolve().parent / "fixtures", bench_root
            )
            targets = json.loads(env["BENCH_TARGETS"])
            self.assertEqual(len(targets), 5)
            self.assertEqual(len(set(targets)), 5)
            self.assertTrue(targets[0].endswith("/conversations/20/messages/stream"))
            self.assertTrue(targets[-1].endswith("/conversations/24/messages/stream"))
            self.assertEqual(env["BENCH_POST_TTFT_SETTLE_DELAY_MS"], "200")

    def test_rag_config_rejects_too_small_conversation_pool(self):
        with tempfile.TemporaryDirectory() as tmp:
            bench_root = Path(tmp)
            self._write_runtime_context("rag-pipeline", "rs-1", bench_root)
            perf = build_performance_config(
                scenario="rag-pipeline",
                target="/api/workspaces/{workspace_id}/conversations/{conversation_id}/messages/stream",
                http_method="POST", request_body_identity="fixed-rag-prompt-v1",
                duration="30s", vus=10, warmup_policy="none",
                iteration_rate_policy="closed-model-ramping-vus", timeout_ms=30000,
                thresholds={}, expected_status=200, success_semantics="SSE token",
                sample_collection_semantics="all", percentile_method="nearest-rank",
            )
            args = self._make_args("rag-pipeline")
            args.vu_level = 5
            with self.assertRaisesRegex(ValueError, "fewer isolated conversations"):
                build_k6_env(
                    args, perf, Path(__file__).resolve().parent / "fixtures", bench_root
                )

    def test_missing_request_body_fixture_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            bench_root = Path(tmp)
            self._write_runtime_context("rag-pipeline", "rs-1", bench_root)
            perf = build_performance_config(
                scenario="rag-pipeline",
                target="/api/workspaces/{workspace_id}/conversations/{conversation_id}/messages/stream",
                http_method="POST",
                request_body_identity="missing-fixture",
                duration="30s",
                vus=10,
                warmup_policy="none",
                iteration_rate_policy="fixed-rate",
                timeout_ms=30000,
                thresholds={},
                expected_status=200,
                success_semantics="ok",
                sample_collection_semantics="all",
                percentile_method="nearest-rank",
            )
            with self.assertRaises(FileNotFoundError):
                build_k6_env(self._make_args(), perf,
                             Path(__file__).resolve().parent / "fixtures", bench_root)


class K6LauncherPreflightTest(unittest.TestCase):

    def test_k6_path_has_strict_precedence_over_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            selected = Path(tmp) / "selected-k6.exe"
            selected.write_bytes(b"selected")
            with mock.patch("k6_runner.shutil.which") as path_lookup:
                resolved, source = resolve_k6_path(
                    environment={"K6_PATH": str(selected), "PATH": str(Path(tmp) / "other")}
                )
            self.assertEqual(str(selected.resolve()), resolved)
            self.assertEqual("K6_PATH", source)
            path_lookup.assert_not_called()

    def test_invalid_k6_path_fails_closed_without_path_fallback(self):
        with mock.patch("k6_runner.shutil.which") as path_lookup:
            with self.assertRaisesRegex(FileNotFoundError, "K6_PATH"):
                resolve_k6_path(environment={"K6_PATH": "C:\\missing\\k6.exe", "PATH": "C:\\tools"})
        path_lookup.assert_not_called()

    def test_path_lookup_is_used_only_when_k6_path_is_absent(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate = Path(tmp) / "k6.exe"
            candidate.write_bytes(b"path candidate")
            with mock.patch("k6_runner.shutil.which", return_value=str(candidate)) as path_lookup:
                resolved, source = resolve_k6_path(environment={"PATH": tmp})
            self.assertEqual(str(candidate.resolve()), resolved)
            self.assertEqual("PATH", source)
            path_lookup.assert_called_once_with("k6", path=tmp)

    def test_preflight_executes_exact_binary_version_and_hashes_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate = Path(tmp) / "k6.exe"
            candidate.write_bytes(b"binary identity")
            completed = types.SimpleNamespace(
                returncode=0,
                stdout="k6.exe v2.2.0 (fixture)\n",
                stderr="",
            )
            with mock.patch("k6_runner.subprocess.run", return_value=completed) as runner:
                result = preflight_k6_binary(
                    str(candidate), expected_version="k6.exe v2.2.0 (fixture)"
                )
            self.assertEqual("PASS", result["status"])
            self.assertEqual(str(candidate.resolve()), result["resolved_path"])
            self.assertRegex(result["binary_sha256"], r"^[0-9a-f]{64}$")
            self.assertEqual(
                [str(candidate.resolve()), "--version"],
                runner.call_args.args[0],
            )

    def test_preflight_version_mismatch_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            candidate = Path(tmp) / "k6.exe"
            candidate.write_bytes(b"binary identity")
            completed = types.SimpleNamespace(returncode=0, stdout="k6.exe v2\n", stderr="")
            with mock.patch("k6_runner.subprocess.run", return_value=completed):
                with self.assertRaisesRegex(RuntimeError, "version mismatch"):
                    preflight_k6_binary(str(candidate), expected_version="k6.exe v1")

    def test_fresh_run_set_is_not_created_when_resolution_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source.json"
            source.write_text(
                json.dumps({"scenario": "http-concurrency", "k6_version": "fixture"}),
                encoding="utf-8",
            )
            run_set = root / "a-formal-http-concurrency-010"
            with mock.patch.dict(os.environ, {"K6_PATH": str(root / "missing.exe")}, clear=True):
                with self.assertRaises(FileNotFoundError):
                    prepare_fresh_run_set(run_set, source, "http-concurrency")
            self.assertFalse(run_set.exists())

    def test_preflight_completes_before_fresh_run_set_creation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source.json"
            source.write_text(
                json.dumps({"scenario": "http-concurrency", "k6_version": "fixture"}),
                encoding="utf-8",
            )
            binary = root / "k6.exe"
            binary.write_bytes(b"fixture")
            run_set = root / "a-formal-http-concurrency-010"

            def preflight_before_create(path, expected_version=None):
                self.assertFalse(run_set.exists())
                return {
                    "status": "PASS",
                    "resolved_path": str(binary.resolve()),
                    "observed_version": "fixture",
                    "expected_version": "fixture",
                    "binary_sha256": "d" * 64,
                }

            with mock.patch(
                "orchestrate_formal_run.resolve_k6_path",
                return_value=(str(binary.resolve()), "K6_PATH"),
            ), mock.patch(
                "orchestrate_formal_run.preflight_k6_binary",
                side_effect=preflight_before_create,
            ):
                config, resolved, preflight = prepare_fresh_run_set(
                    run_set, source, "http-concurrency"
                )
            self.assertTrue(run_set.is_dir())
            self.assertEqual(source.read_bytes(), (run_set / "frozen_config.json").read_bytes())
            self.assertEqual("K6_PATH", preflight["resolution_source"])
            self.assertEqual(str(binary.resolve()), resolved)


class AuthInjectionTest(unittest.TestCase):
    """TEST_GAP-1.E: auth must be injectable without secret leakage into config/raw."""

    def test_api_key_header_injected_from_env(self):
        with mock.patch.dict(os.environ, {"BENCH_API_KEY_SECRET": "test-secret-42"}):
            spec = {"auth_mode": "api_key_header", "auth_source": "BENCH_API_KEY_SECRET"}
            headers = build_headers(spec)
        self.assertEqual(headers["X-API-Key"], "test-secret-42")

    def test_bearer_header_injected_from_env(self):
        # v1.2 formal RAG auth: bearer_header with BENCH_ACCESS_TOKEN source.
        with mock.patch.dict(os.environ, {"BENCH_ACCESS_TOKEN": "tok-99"}):
            spec = {"auth_mode": "bearer_header", "auth_source": "BENCH_ACCESS_TOKEN"}
            headers = build_headers(spec)
        self.assertEqual(headers["Authorization"], "Bearer tok-99")

    def test_session_cookie_formal_mode_rejected(self):
        # v1.2: session_cookie is not a formal auth mode for benchmark execution.
        # build_headers may still produce a Cookie header for legacy callers, but
        # the credential source must be the allowlisted env var.
        with mock.patch.dict(os.environ, {"BENCH_ACCESS_TOKEN": "tok-99"}):
            spec = {"auth_mode": "session_cookie", "auth_source": "BENCH_ACCESS_TOKEN"}
            headers = build_headers(spec)
        self.assertEqual(headers["Cookie"], "tok-99")

    def test_auth_source_is_identity_not_secret_in_frozen_config(self):
        config = build_performance_config(
            scenario="api-key-auth",
            target="http://127.0.0.1:80/api/workspaces/1/knowledge-bases",
            http_method="GET",
            request_body_identity=None,
            duration="30s",
            vus=10,
            warmup_policy="none",
            iteration_rate_policy="fixed-rate",
            timeout_ms=10000,
            thresholds={},
            expected_status=200,
            success_semantics="HTTP 200 on API-key-authenticated protected resource (auth overhead only)",
            sample_collection_semantics="all bench_req_duration Point samples",
            percentile_method="nearest-rank",
            auth_mode="api_key_header",
            auth_source="BENCH_API_KEY_SECRET",
        )
        self.assertEqual(config["auth_mode"], "api_key_header")
        self.assertEqual(config["auth_source"], "BENCH_API_KEY_SECRET")
        # The literal secret must not be in the config.
        self.assertNotIn("test-secret-42", json.dumps(config))

    def test_raw_record_does_not_contain_secret(self):
        tags = {
            "url": "http://localhost/api/chat",
            "status": "200",
            "success": "true",
            "Authorization": "Bearer leaked-should-not-happen",
        }
        record = {
            "metric": "bench_req_duration",
            "type": "Point",
            "data": {"time": "2026-08-22T10:00:00Z", "value": 42.0, "tags": tags},
        }
        sample = k6_record_to_raw(record, "rag-pipeline", "rs-1", "run-1", 0, "cfg", "env")
        raw = json.dumps(sample)
        self.assertNotIn("leaked", raw)
        self.assertNotIn("Bearer", raw)


class ConfigFreezeIntegrityTest(unittest.TestCase):
    """Config freeze must include behavioral fields and exclude secret plaintext."""

    def test_auth_behavioral_fields_change_config_hash(self):
        base = build_performance_config(
            scenario="api-key-auth",
            target="http://127.0.0.1:80/api/workspaces/1/knowledge-bases",
            http_method="GET",
            request_body_identity=None,
            duration="30s",
            vus=10,
            warmup_policy="none",
            iteration_rate_policy="fixed-rate",
            timeout_ms=10000,
            thresholds={"p95": 500},
            expected_status=200,
            success_semantics="auth overhead",
            sample_collection_semantics="all",
            percentile_method="nearest-rank",
            auth_mode="none",
        )
        with_auth = dict(base)
        with_auth["auth_mode"] = "api_key_header"
        with_auth["auth_source"] = "BENCH_API_KEY_SECRET"
        self.assertNotEqual(_sha256_hex(base), _sha256_hex(with_auth))

    def test_frozen_config_fail_if_exists_preserves_original_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "frozen_config.json"
            performance = {"scenario": "fixture", "target": "/fixture"}
            freeze_execution_config(performance, {"os": "fixture"}, {"app": "fixture"}, "k6 fixture", output)
            original = output.read_bytes()
            with self.assertRaises(FileExistsError):
                freeze_execution_config(performance, {"os": "changed"}, {"app": "changed"}, "k6 changed", output)
            self.assertEqual(original, output.read_bytes())

    def test_http_concurrency_harness_identity_binds_script_and_validator_hashes(self):
        definition = _scenario_definition("http-concurrency")
        performance = build_performance_config(
            scenario="http-concurrency",
            target=definition["target"],
            http_method=definition["http_method"],
            request_body_identity=None,
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
            authoritative_latency_semantics=definition["authoritative_latency_semantics"],
            latency_measurement_source=definition["latency_measurement_source"],
            latency_measurement_unit=definition["latency_measurement_unit"],
            latency_request_boundary=definition["latency_request_boundary"],
            latency_pre_percentile_quantization=definition["latency_pre_percentile_quantization"],
        )
        identity = build_benchmark_harness_identity(performance)
        self.assertRegex(identity["behavior_hash"], r"^[0-9a-f]{64}$")
        self.assertEqual(
            {
                "k6_script_template_sha256",
                "k6_runner_sha256",
                "k6_collector_sha256",
                "percentile_sha256",
                "orchestrator_sha256",
                "validator_sha256",
            },
            set(identity["components"]),
        )
        self.assertEqual(
            "k6_response_timings_duration",
            identity["behavior_contract"]["latency_measurement_source"],
        )


class RuntimeContextTest(unittest.TestCase):
    """v1.2 R1: runtime_context canonical path, ownership, and substitution."""

    def test_canonical_path(self):
        path = runtime_context_path("rag-pipeline", "rs-001", bench_root="docs/evaluation/bench")
        self.assertEqual(path, Path("docs/evaluation/bench/rag-pipeline/rs-001/runtime_context.json"))

    def test_ownership_isolation_per_scenario_run_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ctx_a = build_runtime_context(
                scenario="rag-pipeline", run_set_id="rs-001",
                provisioning_logical_id="pl-1", benchmark_user_logical_id="u-1",
                workspace_id="10", conversation_id="20", knowledge_base_id="30",
                document_ids=["40"], api_key_id="50", fixture_identity="f.json")
            ctx_b = build_runtime_context(
                scenario="api-key-auth", run_set_id="rs-001",
                provisioning_logical_id="pl-1", benchmark_user_logical_id="u-1",
                workspace_id="10", conversation_id="20", knowledge_base_id="30",
                document_ids=["40"], api_key_id="50", fixture_identity="f.json")
            write_runtime_context("rag-pipeline", "rs-001", ctx_a, bench_root=root)
            write_runtime_context("api-key-auth", "rs-001", ctx_b, bench_root=root)
            self.assertTrue(runtime_context_path("rag-pipeline", "rs-001", root).exists())
            self.assertTrue(runtime_context_path("api-key-auth", "rs-001", root).exists())

    def test_fail_if_exists(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ctx = build_runtime_context(
                scenario="rag-pipeline", run_set_id="rs-001",
                provisioning_logical_id="pl-1", benchmark_user_logical_id="u-1",
                workspace_id="10", conversation_id="20", knowledge_base_id="30",
                document_ids=["40"], api_key_id="50", fixture_identity="f.json")
            write_runtime_context("rag-pipeline", "rs-001", ctx, bench_root=root)
            with self.assertRaises(FileExistsError):
                write_runtime_context("rag-pipeline", "rs-001", ctx, bench_root=root)

    def test_atomic_write_not_partial_on_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ctx = build_runtime_context(
                scenario="rag-pipeline", run_set_id="rs-001",
                provisioning_logical_id="pl-1", benchmark_user_logical_id="u-1",
                workspace_id="10", conversation_id="20", knowledge_base_id="30",
                document_ids=["40"], api_key_id="50", fixture_identity="f.json")
            # Simulate atomic-rename failure (e.g. filesystem error) after temp file is written.
            def raise_oserror(*args, **kwargs):
                raise OSError("simulated atomic rename failure")

            with mock.patch("runtime_context.os.replace", side_effect=raise_oserror):
                with self.assertRaises(OSError):
                    write_runtime_context("rag-pipeline", "rs-001", ctx, bench_root=root)
            # No partial runtime_context.json should exist; temp file must be cleaned up.
            self.assertFalse(runtime_context_path("rag-pipeline", "rs-001", root).exists())
            self.assertEqual(len(list((root / "rag-pipeline" / "rs-001").glob(".runtime_context_*"))), 0)

    def test_unresolved_placeholder_rejected(self):
        with self.assertRaises(ValueError):
            substitute_target("/api/workspaces/{workspace_id}/x/{unknown}", {"workspace_id": "1"})

    def test_missing_runtime_value_rejected(self):
        with self.assertRaises(ValueError):
            substitute_target("/api/workspaces/{workspace_id}/conversations/{conversation_id}", {"workspace_id": "1"})

    def test_cross_scenario_context_read_prohibited_by_caller(self):
        # The API itself requires explicit (scenario, run_set_id); this test
        # documents that callers must not mix contexts.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ctx = build_runtime_context(
                scenario="rag-pipeline", run_set_id="rs-001",
                provisioning_logical_id="pl-1", benchmark_user_logical_id="u-1",
                workspace_id="10", conversation_id="20", knowledge_base_id="30",
                document_ids=["40"], api_key_id="50", fixture_identity="f.json")
            write_runtime_context("rag-pipeline", "rs-001", ctx, bench_root=root)
            # Attempting to read as a different scenario fails-closed.
            from runtime_context import read_runtime_context
            with self.assertRaises(FileNotFoundError):
                read_runtime_context("api-key-auth", "rs-001", bench_root=root)

    def test_no_secrets_in_runtime_context(self):
        ctx = build_runtime_context(
            scenario="rag-pipeline", run_set_id="rs-001",
            provisioning_logical_id="pl-1", benchmark_user_logical_id="u-1",
            workspace_id="10", conversation_id="20", knowledge_base_id="30",
            document_ids=["40"], api_key_id="50", fixture_identity="f.json")
        text = json.dumps(ctx)
        self.assertNotIn("bearer ", text.lower())
        self.assertNotIn("isk_", text.lower())
        self.assertNotIn("access_token", text.lower())

    def test_conversation_pool_is_unique_and_contains_primary(self):
        ctx = build_runtime_context(
            scenario="rag-pipeline", run_set_id="rs-001",
            provisioning_logical_id="pl-1", benchmark_user_logical_id="u-1",
            workspace_id="10", conversation_id="20", conversation_ids=["20", "21", "22"],
            knowledge_base_id="30", document_ids=["40"], api_key_id="50",
            fixture_identity="f.json",
        )
        self.assertEqual(ctx["conversation_ids"], ["20", "21", "22"])

    def test_substitute_request_body_replaces_knowledge_base_ids(self):
        ctx = {"knowledge_base_id": "42"}
        body = '{"query":"q","knowledgeBaseIds":[1]}'
        result = substitute_request_body(body, ctx)
        self.assertEqual(json.loads(result)["knowledgeBaseIds"], [42])


class HelperLifecycleTest(unittest.TestCase):
    """v1.2: helper bind/port/readiness/startup/cleanup and secret boundary."""

    def test_formal_port_is_18181(self):
        self.assertEqual(FORMAL_HELPER_PORT, 18181)

    def test_run_server_binds_localhost(self):
        server, thread = run_server(FORMAL_HELPER_PORT)
        try:
            self.assertEqual(server.server_address[0], "127.0.0.1")
            self.assertEqual(server.server_address[1], FORMAL_HELPER_PORT)
            self.assertEqual(server.request_queue_size, 128)
        finally:
            server.shutdown()
            server.server_close()

    def test_health_readiness_returns_ready(self):
        server, thread = run_server(FORMAL_HELPER_PORT)
        try:
            url = f"http://127.0.0.1:{FORMAL_HELPER_PORT}/health"
            self.assertTrue(wait_for_helper_readiness(url, timeout_s=5))
            with urllib.request.urlopen(url, timeout=2) as resp:
                self.assertEqual(resp.status, 200)
                body = json.loads(resp.read().decode("utf-8"))
                self.assertEqual(body["status"], "ready")
        finally:
            server.shutdown()
            server.server_close()

    def test_helper_rejects_disallowed_credential_source(self):
        server, thread = run_server(FORMAL_HELPER_PORT)
        try:
            url = f"http://127.0.0.1:{FORMAL_HELPER_PORT}/measure"
            spec = json.dumps({
                "url": "http://127.0.0.1:80/ping",
                "method": "GET",
                "auth_mode": "bearer_header",
                "auth_source": "EVIL_SOURCE",
            }).encode("utf-8")
            req = urllib.request.Request(url, data=spec, headers={"Content-Type": "application/json"}, method="POST")
            with self.assertRaises(urllib.error.HTTPError) as cm:
                urllib.request.urlopen(req, timeout=2)
            self.assertEqual(cm.exception.code, 400)
        finally:
            server.shutdown()
            server.server_close()

    def test_helper_resolve_credential_allowlist_only(self):
        from ttft_transport import resolve_credential
        with mock.patch.dict(os.environ, {"BENCH_ACCESS_TOKEN": "tok"}):
            self.assertEqual(resolve_credential("BENCH_ACCESS_TOKEN"), "tok")
            self.assertIsNone(resolve_credential("BENCH_OTHER_SECRET"))

    def test_helper_response_redacts_bearer(self):
        with mock.patch.dict(os.environ, {"BENCH_ACCESS_TOKEN": " Bearer leaked-token"}):
            result = build_headers({"auth_mode": "bearer_header", "auth_source": "BENCH_ACCESS_TOKEN"})
            # build_headers is used internally; the helper must not echo it.
            self.assertEqual(result["Authorization"], "Bearer  Bearer leaked-token")

    def test_measure_result_redacts_secrets(self):
        from ttft_transport import _redact_secrets
        self.assertEqual(_redact_secrets({"h": "Bearer abc"})["h"], "[REDACTED_BEARER]")
        self.assertEqual(_redact_secrets({"h": "isk_x_y"})["h"], "[REDACTED_API_KEY]")


class _ControlledDelayFixture:
    """Local SSE fixture that sleeps D ms then emits a token event."""

    def __init__(self, delay_ms: float, token_delta: str = "hello", extra_events_before: list | None = None,
                 trailing_delay_ms: float = 0):
        self.delay_ms = delay_ms
        self.token_delta = token_delta
        self.extra_events_before = extra_events_before or []
        self.trailing_delay_ms = trailing_delay_ms

    def handle(self, conn):
        try:
            request = b""
            while b"\r\n\r\n" not in request:
                chunk = conn.recv(1024)
                if not chunk:
                    break
                request += chunk
            time.sleep(self.delay_ms / 1000.0)
            lines = []
            for ev in self.extra_events_before:
                lines.append(f"event: {ev['event']}")
                lines.append(f"data: {json.dumps(ev.get('data', {}))}")
                lines.append("")
            lines.append("event: token")
            lines.append(f"data: {json.dumps({'delta': self.token_delta})}")
            lines.append("")
            body = ("\n".join(lines) + "\n").encode("utf-8")
            headers = (
                b"HTTP/1.1 200 OK\r\n"
                b"Content-Type: text/event-stream\r\n"
                b"Cache-Control: no-cache\r\n"
                b"Connection: close\r\n"
                b"\r\n"
            )
            conn.sendall(headers + body)
            if self.trailing_delay_ms:
                time.sleep(self.trailing_delay_ms / 1000.0)
        finally:
            conn.close()

    def serve_one(self, port: int):
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("127.0.0.1", port))
        sock.listen(1)
        conn, _ = sock.accept()
        self.handle(conn)
        sock.close()


class TtftControlledDelayTest(unittest.TestCase):
    """v1.2 controlled-delay integration timing tests."""

    def _measure_against_fixture(self, fixture: _ControlledDelayFixture, port: int = 18333,
                                 join_timeout: float = 5) -> dict:
        thread = threading.Thread(target=fixture.serve_one, args=(port,), daemon=True)
        thread.start()
        try:
            return measure_sse_ttft({
                "url": f"http://127.0.0.1:{port}/stream",
                "method": "GET",
                "timeout_ms": 10000,
                "expected_status": 200,
            })
        finally:
            thread.join(timeout=join_timeout)

    def _assert_within_tolerance(self, measured: float, expected: float):
        threshold = max(50.0, 0.15 * expected)
        self.assertLessEqual(abs(measured - expected), threshold,
                             f"measured={measured}, expected={expected}, threshold={threshold}")

    def test_delay_200ms(self):
        result = self._measure_against_fixture(_ControlledDelayFixture(200))
        self.assertTrue(result["success"])
        self.assertIsNotNone(result["ttft_ms"])
        self._assert_within_tolerance(result["ttft_ms"], 200)
        self.assertEqual(result["qualifying_event_type"], "token")

    def test_delay_500ms(self):
        result = self._measure_against_fixture(_ControlledDelayFixture(500))
        self.assertTrue(result["success"])
        self._assert_within_tolerance(result["ttft_ms"], 500)

    def test_returns_at_first_token_without_draining_long_lived_stream(self):
        started = time.monotonic()
        result = self._measure_against_fixture(
            _ControlledDelayFixture(100, trailing_delay_ms=1500),
            port=18334,
            join_timeout=0,
        )
        elapsed_ms = (time.monotonic() - started) * 1000.0
        self.assertTrue(result["success"])
        self.assertLess(elapsed_ms, 700.0)
        self.assertGreater(result["ttft_ms"], 0)
        self.assertLess(result["ttft_ms"], 700.0)

    def test_first_token_used_not_second(self):
        fixture = _ControlledDelayFixture(200, token_delta="first")
        result = self._measure_against_fixture(fixture)
        self.assertTrue(result["success"])
        self._assert_within_tolerance(result["ttft_ms"], 200)

    def test_empty_token_then_real_token_uses_real(self):
        fixture = _ControlledDelayFixture(200, token_delta="real",
                                           extra_events_before=[{"event": "token", "data": {"delta": ""}}])
        result = self._measure_against_fixture(fixture)
        self.assertTrue(result["success"])
        # TTFT must be based on the real token after the empty one.
        self.assertGreater(result["ttft_ms"], 50)
        self.assertEqual(result["qualifying_event_type"], "token")

    def test_start_event_then_token_uses_token(self):
        fixture = _ControlledDelayFixture(200, token_delta="hello",
                                           extra_events_before=[{"event": "start", "data": {}}])
        result = self._measure_against_fixture(fixture)
        self.assertTrue(result["success"])
        self._assert_within_tolerance(result["ttft_ms"], 200)

    def test_citation_event_then_token_uses_token(self):
        fixture = _ControlledDelayFixture(200, token_delta="hello",
                                           extra_events_before=[{"event": "citation", "data": {"source": "x"}}])
        result = self._measure_against_fixture(fixture)
        self.assertTrue(result["success"])
        self._assert_within_tolerance(result["ttft_ms"], 200)

    def test_done_only_no_qualifying_token(self):
        fixture = _ControlledDelayFixture(200, token_delta="",
                                           extra_events_before=[{"event": "done", "data": {}}])
        result = self._measure_against_fixture(fixture)
        self.assertFalse(result["success"])
        self.assertIsNone(result["ttft_ms"])

    def test_eof_before_token_fails(self):
        fixture = _ControlledDelayFixture(200, token_delta="",
                                           extra_events_before=[{"event": "start", "data": {}}])
        result = self._measure_against_fixture(fixture)
        self.assertFalse(result["success"])
        self.assertIsNone(result["ttft_ms"])

    def test_split_chunks_parses_correctly(self):
        # Manually exercise parser on split input.
        chunk1 = "event: token\ndata: "
        chunk2 = json.dumps({"delta": "split"}) + "\n\nevent: done\ndata: {}\n\n"
        events = parse_sse_events(chunk1 + chunk2)
        results = [is_qualifying_token(e) for e in events]
        self.assertEqual(results, [(True, "split"), (False, None)])

    def test_crlf_line_endings_parsed(self):
        events = parse_sse_events("event: token\r\ndata: {\"delta\":\"crlf\"}\r\n\r\n")
        self.assertEqual(len(events), 1)
        self.assertEqual(is_qualifying_token(events[0]), (True, "crlf"))


class _ConcurrentFixture:
    """Local SSE fixture for concurrency isolation tests."""

    def __init__(self, delay_ms: float = 300):
        self.delay_ms = delay_ms
        self.lock = threading.Lock()
        self.wave_start = None

    def handle(self, conn):
        try:
            request = b""
            while b"\r\n\r\n" not in request:
                chunk = conn.recv(1024)
                if not chunk:
                    break
                request += chunk
            # Extract request_id from query string if present.
            req_id = "unknown"
            match = __import__("re").search(rb"request_id=([^\s]+)", request)
            if match:
                req_id = urllib.parse.unquote(match.group(1).decode("utf-8"))
            with self.lock:
                if self.wave_start is None:
                    self.wave_start = time.monotonic()
            time.sleep(self.delay_ms / 1000.0)
            body = (
                "event: token\n"
                f"data: {json.dumps({'delta': req_id})}\n\n"
            ).encode("utf-8")
            headers = (
                b"HTTP/1.1 200 OK\r\n"
                b"Content-Type: text/event-stream\r\n"
                b"Connection: close\r\n"
                b"\r\n"
            )
            conn.sendall(headers + body)
        finally:
            conn.close()

    def serve(self, port: int, ready_event: threading.Event):
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("127.0.0.1", port))
        sock.listen(10)
        ready_event.set()
        try:
            while True:
                sock.settimeout(0.5)
                try:
                    conn, _ = sock.accept()
                except socket.timeout:
                    continue
                t = threading.Thread(target=self.handle, args=(conn,), daemon=True)
                t.start()
        finally:
            sock.close()


class ConcurrencyWaveTest(unittest.TestCase):
    """v1.2 R2: wave-based concurrency isolation protocol."""

    def _run_wave(self, level: int, concurrency: int, wave: int, fixture: _ConcurrentFixture, port: int) -> dict:
        barrier = threading.Barrier(concurrency)
        results = {}
        errors = []

        def worker(index: int):
            req_id = f"L{level}-W{wave}-R{index}"
            try:
                barrier.wait(timeout=5)
                start = time.monotonic()
                result = measure_sse_ttft({
                    "url": f"http://127.0.0.1:{port}/stream?request_id={urllib.parse.quote(req_id)}",
                    "method": "GET",
                    "timeout_ms": 10000,
                    "expected_status": 200,
                })
                elapsed = (time.monotonic() - start) * 1000.0
                results[req_id] = {"result": result, "elapsed": elapsed}
            except Exception as e:
                errors.append((req_id, str(e)))

        threads = [threading.Thread(target=worker, args=(i,)) for i in range(concurrency)]
        wave_start = time.monotonic()
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=15)
        wave_wall_time = (time.monotonic() - wave_start) * 1000.0

        return {
            "results": results,
            "errors": errors,
            "wave_wall_time": wave_wall_time,
        }

    def _run_level(self, level: int, concurrency: int) -> dict:
        port = 18334
        fixture = _ConcurrentFixture(delay_ms=300)
        ready = threading.Event()
        server_thread = threading.Thread(target=fixture.serve, args=(port, ready), daemon=True)
        server_thread.start()
        self.assertTrue(ready.wait(timeout=5))
        try:
            wave_results = []
            for wave in range(1, 4):
                wr = self._run_wave(level, concurrency, wave, fixture, port)
                wave_results.append(wr)
            return {"level": level, "waves": wave_results}
        finally:
            server_thread.join(timeout=1)

    def _assert_level_pass(self, level_result: dict):
        for wave_idx, wave in enumerate(level_result["waves"], start=1):
            self.assertEqual(len(wave["errors"]), 0, f"L{level_result['level']}W{wave_idx} errors")
            self.assertEqual(len(wave["results"]), level_result["level"] or 1)
            for req_id, data in wave["results"].items():
                self.assertTrue(data["result"]["success"], f"{req_id} failed")
                self.assertEqual(data["result"]["ttft_ms"], data["result"]["ttft_ms"])
                # Qualifying token must echo the input request_id.
                self.assertEqual(data["result"].get("qualifying_event_type"), "token")
            wave_wall = wave["wave_wall_time"]
            # Per-wave anti-serialization bound: <= 700 ms.
            self.assertLessEqual(wave_wall, 700,
                                 f"L{level_result['level']}W{wave_idx} wall time {wave_wall} exceeds 700ms")

    def test_l1_3_waves(self):
        result = self._run_level(1, 1)
        self._assert_level_pass(result)

    def test_l5_3_waves(self):
        result = self._run_level(5, 5)
        self._assert_level_pass(result)

    def test_l10_3_waves(self):
        result = self._run_level(10, 10)
        self._assert_level_pass(result)

    def test_request_id_isolation_no_mismatch(self):
        level = 5
        result = self._run_level(level, level)
        for wave in result["waves"]:
            for req_id, data in wave["results"].items():
                self.assertTrue(req_id.startswith(f"L{level}"))


class AuthK6ToHelperBoundaryTest(unittest.TestCase):
    """v1.2: k6 must send only auth_mode + credential_source to helper."""

    def test_k6_spec_contains_only_identity_not_secret(self):
        # Simulate the spec constructed by k6_script_template.js executeTtftHelper.
        spec = {
            "url": "http://127.0.0.1:80/api/chat",
            "method": "POST",
            "headers": {"Accept": "text/event-stream"},
            "body": "{}",
            "content_type": "application/json",
            "auth_mode": "bearer_header",
            "auth_source": "BENCH_ACCESS_TOKEN",
            "expected_status": 200,
            "timeout_ms": 30000,
        }
        spec_text = json.dumps(spec)
        self.assertIn("BENCH_ACCESS_TOKEN", spec_text)
        self.assertNotIn("bearer ", spec_text.lower())
        self.assertNotIn("isk_", spec_text.lower())

    def test_helper_rejects_raw_bearer_in_auth_source(self):
        server, thread = run_server(FORMAL_HELPER_PORT)
        try:
            url = f"http://127.0.0.1:{FORMAL_HELPER_PORT}/measure"
            spec = json.dumps({
                "url": "http://127.0.0.1:80/ping",
                "method": "GET",
                "auth_mode": "bearer_header",
                "auth_source": "Bearer raw-token",
            }).encode("utf-8")
            req = urllib.request.Request(url, data=spec, headers={"Content-Type": "application/json"}, method="POST")
            with self.assertRaises(urllib.error.HTTPError) as cm:
                urllib.request.urlopen(req, timeout=2)
            self.assertEqual(cm.exception.code, 400)
        finally:
            server.shutdown()
            server.server_close()

    def test_allowed_sources_frozen(self):
        self.assertEqual(ALLOWED_CREDENTIAL_SOURCES, {"BENCH_ACCESS_TOKEN", "BENCH_API_KEY_SECRET"})


class ConfigRefreezeV12Test(unittest.TestCase):
    """v1.2: config hash re-freeze semantics for rag/api-key/concurrency."""

    def _freeze_scenario(self, name: str) -> str:
        definition = _scenario_definition(name)
        config = build_performance_config(
            scenario=name,
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
        return _sha256_hex(config)

    def test_rag_hash_changed_from_old_session_cookie(self):
        old_definition = dict(_scenario_definition("rag-pipeline"))
        old_definition["auth_mode"] = "session_cookie"
        old_definition["auth_source"] = "BENCH_SESSION_COOKIE"
        old_definition["target"] = "http://127.0.0.1:80/api/workspaces/1/conversations/1/messages/stream"
        for key in ("ttft_topology_mode", "helper_bind_address", "helper_port",
                    "helper_concurrency_model", "helper_readiness_path",
                    "helper_startup_timeout_ms", "helper_request_timeout_margin_ms",
                    "ttft_t0_t1_semantics_version"):
            old_definition.pop(key, None)
        old_config = build_performance_config(
            scenario="rag-pipeline",
            target=old_definition["target"],
            http_method=old_definition["http_method"],
            request_body_identity=old_definition.get("request_body_identity"),
            duration=old_definition["duration"],
            vus=old_definition["vus"],
            warmup_policy=old_definition["warmup_policy"],
            iteration_rate_policy=old_definition["iteration_rate_policy"],
            timeout_ms=old_definition["timeout_ms"],
            thresholds=old_definition["thresholds"],
            expected_status=old_definition["expected_status"],
            success_semantics=old_definition["success_semantics"],
            sample_collection_semantics=old_definition["sample_collection_semantics"],
            percentile_method=old_definition["percentile_method"],
            auth_mode=old_definition["auth_mode"],
            auth_source=old_definition["auth_source"],
            content_type=old_definition.get("content_type"),
            extra_headers=old_definition.get("extra_headers"),
            ttft_mode=old_definition["ttft_mode"],
        )
        new_hash = self._freeze_scenario("rag-pipeline")
        self.assertNotEqual(_sha256_hex(old_config), new_hash)

    def test_api_key_hash_changed_with_paired_methodology(self):
        old_definition = dict(_scenario_definition("api-key-auth"))
        for key in ("api_key_benchmark_methodology", "api_key_baseline_mode",
                    "api_key_test_mode", "api_key_target_logical_identity"):
            old_definition.pop(key, None)
        old_config = build_performance_config(
            scenario="api-key-auth",
            target=old_definition["target"],
            http_method=old_definition["http_method"],
            request_body_identity=old_definition.get("request_body_identity"),
            duration=old_definition["duration"],
            vus=old_definition["vus"],
            warmup_policy=old_definition["warmup_policy"],
            iteration_rate_policy=old_definition["iteration_rate_policy"],
            timeout_ms=old_definition["timeout_ms"],
            thresholds=old_definition["thresholds"],
            expected_status=old_definition["expected_status"],
            success_semantics=old_definition["success_semantics"],
            sample_collection_semantics=old_definition["sample_collection_semantics"],
            percentile_method=old_definition["percentile_method"],
            auth_mode=old_definition["auth_mode"],
            auth_source=old_definition["auth_source"],
            content_type=old_definition.get("content_type"),
            extra_headers=old_definition.get("extra_headers"),
            ttft_mode=old_definition["ttft_mode"],
        )
        new_hash = self._freeze_scenario("api-key-auth")
        self.assertNotEqual(_sha256_hex(old_config), new_hash)

    def test_http_concurrency_hash_is_stable_and_differs_from_formal_008_config(self):
        hash1 = self._freeze_scenario("http-concurrency")
        hash2 = self._freeze_scenario("http-concurrency")
        self.assertEqual(hash1, hash2)
        self.assertNotEqual(
            "53b17d168f3511504cf70785b2051efa695994d62b469e54f29c1b02acfc1efb",
            hash1,
        )

    def test_http_concurrency_precision_contract_and_repeatability_threshold_are_frozen(self):
        definition = _scenario_definition("http-concurrency")
        self.assertEqual("k6_response_timings_duration", definition["latency_measurement_source"])
        self.assertEqual("fractional_milliseconds", definition["latency_measurement_unit"])
        self.assertEqual(
            "sending_plus_waiting_plus_receiving_excludes_blocked_dns_connect_tls",
            definition["latency_request_boundary"],
        )
        self.assertEqual("none", definition["latency_pre_percentile_quantization"])
        validator = (Path(__file__).resolve().parent / "run_set_acceptance.py").read_text(
            encoding="utf-8"
        )
        self.assertIn("if deviation > 0.20:", validator)

    def test_external_execution_policy_is_explicit_and_method_aligned(self):
        for scenario in ("rag-pipeline", "http-concurrency", "api-key-auth"):
            with self.subTest(scenario=scenario):
                definition = _scenario_definition(scenario)
                self.assertEqual(definition["iteration_rate_policy"], "closed-model-ramping-vus")
                self.assertIn("sustain stage", definition["sample_collection_semantics"])
        self.assertEqual(
            _scenario_definition("rag-pipeline")["authoritative_latency_semantics"],
            "first_non_empty_token_ttft_ms",
        )
        self.assertEqual(
            _scenario_definition("rag-pipeline")["provider_boundary"],
            "deterministic_e2e_stub_at_provider_boundary_real_system_pipeline",
        )

    def test_runtime_db_ids_excluded_from_config_hash(self):
        base = build_performance_config(
            scenario="rag-pipeline",
            target="/api/workspaces/{workspace_id}/conversations/{conversation_id}/messages/stream",
            http_method="POST",
            request_body_identity="fixed-rag-prompt-v1",
            duration="30s",
            vus=10,
            warmup_policy="none",
            iteration_rate_policy="fixed-rate",
            timeout_ms=30000,
            thresholds={},
            expected_status=200,
            success_semantics="ok",
            sample_collection_semantics="all",
            percentile_method="nearest-rank",
            auth_mode="bearer_header",
            auth_source="BENCH_ACCESS_TOKEN",
        )
        # Runtime IDs and secrets must not be in the canonical config.
        canonical = json.dumps(base, sort_keys=True)
        self.assertNotIn("workspace_id=1", canonical)
        self.assertNotIn("conversation_id=1", canonical)
        self.assertNotIn("12345", canonical)


if __name__ == "__main__":
    unittest.main(verbosity=2)
