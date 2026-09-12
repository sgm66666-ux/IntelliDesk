#!/usr/bin/env python3
"""k6 benchmark runner for Phase 8 Wave 2.

This script orchestrates a k6 run and persists per-sample raw output.
It is intended for formal benchmark execution only after Independent Review.
It does NOT execute a benchmark by itself.

v1.2 responsibilities:
- Load the run-set's runtime_context.json and substitute placeholders.
- Start the TTFT helper, wait for readiness, and shut it down in finally.
- Inject runtime secrets only via environment variables.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from k6_collector import collect_k6_output
from runtime_context import (
    load_and_substitute,
    read_runtime_context,
    runtime_context_path,
    substitute_target,
)
from ttft_transport import FORMAL_HELPER_PORT, run_server


FORMAL_HELPER_BIND = "127.0.0.1"
FORMAL_HELPER_READINESS_URL = f"http://{FORMAL_HELPER_BIND}:{FORMAL_HELPER_PORT}/health"
FORMAL_HELPER_STARTUP_TIMEOUT_S = 30


def _validated_k6_file(value: str, source: str) -> str:
    if not value or not value.strip():
        raise FileNotFoundError(f"{source} is blank")
    candidate = Path(value).expanduser()
    if not candidate.is_file():
        raise FileNotFoundError(f"{source} does not name an existing file: {value}")
    return str(candidate.resolve())


def resolve_k6_path(
    k6_path: str | None = None,
    environment: dict[str, str] | None = None,
) -> tuple[str, str]:
    """Resolve k6 with strict K6_PATH precedence and no silent fallback.

    Returns (absolute_path, resolution_source). When K6_PATH is present it is
    the only candidate. PATH lookup is permitted only when K6_PATH is absent.
    A non-default explicit k6_path is retained for non-Formal callers/tests and
    is also validated strictly.
    """
    env = os.environ if environment is None else environment
    if "K6_PATH" in env:
        return _validated_k6_file(env.get("K6_PATH", ""), "K6_PATH"), "K6_PATH"
    if k6_path and k6_path != "k6":
        return _validated_k6_file(k6_path, "explicit k6 path"), "explicit"
    resolved = shutil.which("k6", path=env.get("PATH"))
    if not resolved:
        raise FileNotFoundError("K6_PATH is unset and k6 was not found on PATH")
    return _validated_k6_file(resolved, "PATH-resolved k6"), "PATH"


def preflight_k6_binary(k6_path: str, expected_version: str | None = None) -> dict:
    """Execute --version on the exact resolved binary and verify the freeze."""
    resolved = _validated_k6_file(k6_path, "preflight k6 path")
    completed = subprocess.run(
        [resolved, "--version"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=15,
    )
    output = (completed.stdout or completed.stderr or "").strip()
    if completed.returncode != 0:
        raise RuntimeError(
            f"k6 --version preflight failed with exit {completed.returncode}: {output}"
        )
    if not output:
        raise RuntimeError("k6 --version preflight returned empty output")
    observed_version = output.splitlines()[0].strip()
    if expected_version and observed_version != expected_version:
        raise RuntimeError(
            f"k6 version mismatch: frozen={expected_version!r}, observed={observed_version!r}"
        )
    import hashlib
    digest = hashlib.sha256()
    with open(resolved, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return {
        "status": "PASS",
        "resolved_path": resolved,
        "observed_version": observed_version,
        "expected_version": expected_version,
        "binary_sha256": digest.hexdigest(),
    }


def load_config(config_path: Path) -> dict:
    with open(config_path, "r", encoding="utf-8") as f:
        return json.load(f)


def extract_hashes(config: dict) -> tuple[str | None, str | None]:
    return config.get("config_hash"), config.get("environment_hash")


def ensure_run_set_dir(bench_root: Path, scenario: str, run_set_id: str) -> Path:
    run_set_dir = bench_root / scenario / run_set_id
    if not run_set_dir.exists():
        raise FileNotFoundError(
            f"Run-set directory does not exist; use BenchmarkRunSetManager to create it first: {run_set_dir}"
        )
    return run_set_dir


def load_request_body(identity: str | None, fixture_dir: Path) -> str | None:
    """Load a deterministic request body from a fixture file."""
    if not identity:
        return None
    fixture_path = fixture_dir / f"{identity}.json"
    if not fixture_path.exists():
        raise FileNotFoundError(f"request body fixture not found: {fixture_path}")
    return fixture_path.read_text(encoding="utf-8").strip()


def wait_for_helper_readiness(url: str, timeout_s: float) -> bool:
    """Poll the helper /health endpoint until it returns ready or timeout."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            req = urllib.request.Request(url, method="GET")
            with urllib.request.urlopen(req, timeout=2) as resp:
                if resp.status == 200:
                    body = json.loads(resp.read().decode("utf-8"))
                    if body.get("status") == "ready":
                        return True
        except Exception:
            pass
        time.sleep(0.2)
    return False


def start_helper(port: int = FORMAL_HELPER_PORT) -> tuple:
    """Start the formal TTFT helper server and wait for readiness.

    Returns (server, thread). Raises RuntimeError on startup failure.
    """
    server, thread = run_server(port)
    if not wait_for_helper_readiness(FORMAL_HELPER_READINESS_URL, FORMAL_HELPER_STARTUP_TIMEOUT_S):
        server.shutdown()
        raise RuntimeError(f"helper did not become ready within {FORMAL_HELPER_STARTUP_TIMEOUT_S}s")
    return server, thread


def stop_helper(server) -> None:
    """Gracefully shut down the helper server."""
    if server is not None:
        try:
            server.shutdown()
            server.server_close()
        except Exception:
            pass


def build_k6_env(
    args: argparse.Namespace,
    perf: dict,
    fixture_dir: Path,
    bench_root: Path,
) -> dict:
    """Build the environment variables consumed by k6_script_template.js."""
    env = os.environ.copy()

    # v1.2: target template is logical; runtime IDs come from runtime_context.json.
    # No fallback target is allowed; the frozen config must provide it.
    target_template = perf.get("target")
    if not target_template:
        raise ValueError("frozen performance_config missing required field: target")
    request_body_identity = perf.get("request_body_identity")

    effective_target, effective_body = load_and_substitute(
        scenario=args.scenario,
        run_set_id=args.run_set_id,
        target_template=target_template,
        request_body_identity=request_body_identity,
        fixture_dir=fixture_dir,
        bench_root=bench_root,
    )

    if args.target:
        env["BENCH_TARGET"] = args.target
    else:
        env["BENCH_TARGET"] = effective_target

    if args.scenario == "rag-pipeline" and not args.target:
        context = read_runtime_context(args.scenario, args.run_set_id, bench_root)
        conversation_ids = context.get("conversation_ids") or [context["conversation_id"]]
        required_count = args.vu_level if args.vu_level is not None else perf.get("vus", 1)
        if len(conversation_ids) < required_count:
            raise ValueError(
                "RAG runtime context has fewer isolated conversations "
                f"({len(conversation_ids)}) than requested VUs ({required_count})"
            )
        targets = []
        for conversation_id in conversation_ids[:required_count]:
            target_context = dict(context)
            target_context["conversation_id"] = str(conversation_id)
            targets.append(substitute_target(target_template, target_context))
        env["BENCH_TARGETS"] = json.dumps(targets, ensure_ascii=False, separators=(",", ":"))

    # Formal run ID encodes VU level and independent-run index, e.g. "vu1-run-1".
    if args.run_id and args.run_id != "run-1":
        run_id = args.run_id
    elif args.vu_level is not None and args.independent_run_index is not None:
        run_id = f"vu{args.vu_level}-run-{args.independent_run_index}"
    else:
        run_id = args.run_id

    env["BENCH_SCENARIO"] = args.scenario
    env["BENCH_RUN_SET_ID"] = args.run_set_id
    env["BENCH_RUN_ID"] = run_id
    env["BENCH_VU_LEVEL"] = str(args.vu_level if args.vu_level is not None else perf.get("vus", 1))
    env["BENCH_RAMP_DURATION"] = perf.get("ramp_duration", "5s")
    env["BENCH_SUSTAIN_DURATION"] = perf.get("sustain_duration", "30s")
    env["BENCH_WARMUP_POLICY"] = perf.get("warmup_policy", "exclude")
    env["BENCH_HTTP_METHOD"] = perf.get("http_method", "GET")
    env["BENCH_REQUEST_BODY"] = effective_body or ""
    env["BENCH_CONTENT_TYPE"] = perf.get("content_type") or "application/json"
    env["BENCH_EXTRA_HEADERS"] = json.dumps(
        perf.get("extra_headers") or {}, ensure_ascii=False, separators=(",", ":")
    )
    env["BENCH_AUTH_MODE"] = args.auth_mode if args.auth_mode else perf.get("auth_mode", "none")
    env["BENCH_AUTH_SOURCE"] = args.auth_source if args.auth_source else (perf.get("auth_source") or "")
    env["BENCH_PROVIDER_MODE"] = (perf.get("metadata") or {}).get(
        "provider_mode", "real"
    )
    env["BENCH_TTFT_MODE"] = "true" if perf.get("ttft_mode") else "false"
    env["BENCH_POST_TTFT_SETTLE_DELAY_MS"] = str(perf.get("post_ttft_settle_delay_ms", 0))
    env["BENCH_TTFT_HELPER_URL"] = f"http://{FORMAL_HELPER_BIND}:{FORMAL_HELPER_PORT}/measure"
    env["BENCH_EXPECTED_STATUS"] = str(perf.get("expected_status", 200))
    env["BENCH_TIMEOUT_MS"] = str(perf.get("timeout_ms", 30000))
    env["BENCH_RUN_START_TIME"] = datetime.now(timezone.utc).isoformat()
    return env


def run_k6(args: argparse.Namespace, env: dict, start_helper_server: bool = True) -> dict:
    if getattr(args, "k6_preflighted", False):
        # Formal orchestration already resolved and version-checked this exact
        # absolute file. Do not perform a second lookup or fallback.
        k6_path = _validated_k6_file(args.k6_path, "preflighted k6 path")
    else:
        k6_path, _ = resolve_k6_path(args.k6_path)
    script_dir = Path(__file__).resolve().parent
    script_template = script_dir / "k6_script_template.js"
    if not script_template.exists():
        raise FileNotFoundError(f"k6 script template not found: {script_template}")

    run_set_dir = ensure_run_set_dir(
        Path(args.bench_root), args.scenario, args.run_set_id
    )
    k6_output_filename = f"k6_output_{args.run_id}.json"
    k6_output_path = run_set_dir / k6_output_filename

    server = None
    if start_helper_server and env.get("BENCH_TTFT_MODE") == "true":
        server, _ = start_helper()

    try:
        cmd = [
            k6_path,
            "run",
            str(script_template),
            "--out",
            f"json={k6_output_filename}",
        ]

        result = subprocess.run(
            cmd,
            cwd=str(run_set_dir),
            env=env,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=300,
        )
    finally:
        stop_helper(server)

    return {
        "returncode": result.returncode,
        "stdout": result.stdout or "",
        "stderr": result.stderr or "",
        "k6_output_path": k6_output_path,
        "run_set_dir": run_set_dir,
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Wave 2 k6 benchmark runner")
    parser.add_argument("--scenario", required=True, help="Benchmark scenario name")
    parser.add_argument("--run-set-id", required=True, help="Run-set id")
    parser.add_argument("--run-id", default="run-1", help="Run id (overrides --vu-level/--independent-run-index)")
    parser.add_argument("--target", default=None, help="Target endpoint URL (overrides substitution)")
    parser.add_argument("--duration", default="30s", help="Legacy sustain duration; use frozen config ramp/sustain instead")
    parser.add_argument("--vus", type=int, default=1, help="Legacy VU count; use --vu-level for formal matrix")
    parser.add_argument("--vu-level", type=int, default=None, help="Formal VU level: 1/5/10")
    parser.add_argument("--independent-run-index", type=int, default=None, help="Independent run index within a level, e.g. 1/2/3")
    parser.add_argument("--mode", default=None, help="Mode name for raw file naming (default: scenario)")
    parser.add_argument("--k6-path", default=None, help="explicit k6 binary path; K6_PATH still has precedence")
    parser.add_argument("--bench-root", default="docs/evaluation/bench", help="Benchmark root dir")
    parser.add_argument("--config-path", help="Path to frozen benchmark config JSON")
    parser.add_argument("--no-helper", action="store_true", help="Do not start the TTFT helper")
    parser.add_argument("--auth-mode", default=None, help="Override config auth_mode for paired comparison runs")
    parser.add_argument("--auth-source", default=None, help="Override config auth_source for paired comparison runs")
    args = parser.parse_args(argv)

    config = {}
    config_hash = None
    environment_hash = None
    perf = {}
    if args.config_path:
        config = load_config(Path(args.config_path))
        config_hash, environment_hash = extract_hashes(config)
        perf = config.get("performance_config", {})

    fixture_dir = Path(__file__).resolve().parent / "fixtures"
    bench_root = Path(args.bench_root)

    # Fail-closed: runtime_context must exist for the current scenario/run-set.
    ctx_path = runtime_context_path(args.scenario, args.run_set_id, bench_root)
    if not ctx_path.exists():
        print(
            json.dumps(
                {"status": "FAIL", "reason": f"runtime_context missing: {ctx_path}"},
                ensure_ascii=False,
                indent=2,
            )
        )
        return 1

    env = build_k6_env(args, perf, fixture_dir, bench_root)
    result = run_k6(args, env, start_helper_server=not args.no_helper)
    if result["returncode"] != 0:
        print(json.dumps({"status": "FAIL", "reason": result["stderr"]}, ensure_ascii=False, indent=2))
        return result["returncode"]

    collect_k6_output(
        k6_output_path=result["k6_output_path"],
        run_set_dir=result["run_set_dir"],
        scenario=args.scenario,
        run_set_id=args.run_set_id,
        run_id=env.get("BENCH_RUN_ID", args.run_id),
        mode=args.mode or args.scenario,
        config_hash=config_hash,
        environment_hash=environment_hash,
        run_start_time=env.get("BENCH_RUN_START_TIME"),
    )

    print(json.dumps({"status": "PASS", "run_set_dir": str(result["run_set_dir"])}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
