#!/usr/bin/env python3
"""Minimal RAG TTFT smoke — NOT_BENCHMARK_EVIDENCE.

Provisions fresh resources, runs one SSE TTFT measurement through the real
k6→helper→Nginx→backend path, asserts HTTP/SSE success and non-null TTFT fields,
then cleans up the transient credential file.
"""

import json
import os
import subprocess
import sys
import uuid
from pathlib import Path

# Import project benchmark tooling.
from provisioning import provision_run_set
from runtime_context import substitute_request_body, substitute_target
from cleanup import cleanup_run_set

BENCH_ROOT = Path("docs/evaluation/bench")
SCENARIO = "rag-pipeline"
RUN_SET_ID = f"smoke-ttft-{uuid.uuid4().hex[:8]}"
BASE_URL = "http://127.0.0.1:80"
FIXTURE = Path(__file__).resolve().parent / "fixtures" / "benchmark-doc.txt"


def main() -> int:
    run_set_dir = BENCH_ROOT / SCENARIO / RUN_SET_ID
    run_set_dir.mkdir(parents=True, exist_ok=True)

    try:
        context = provision_run_set(
            scenario=SCENARIO,
            run_set_id=RUN_SET_ID,
            base_url=BASE_URL,
            fixture_path=FIXTURE,
            bench_root=str(BENCH_ROOT),
            inject_env=True,
        )
    except Exception as e:
        print(json.dumps({"status": "FAIL", "phase": "provisioning", "reason": str(e)}, ensure_ascii=False), file=sys.stderr)
        return 1

    access_token = os.environ.get("BENCH_ACCESS_TOKEN")
    if not access_token:
        print(json.dumps({"status": "FAIL", "phase": "credential", "reason": "BENCH_ACCESS_TOKEN not set"}, ensure_ascii=False), file=sys.stderr)
        return 1

    target_template = "/api/workspaces/{workspace_id}/conversations/{conversation_id}/messages/stream"
    target = substitute_target(target_template, context)

    body_fixture = '{"query":"benchmark query for RAG pipeline","knowledgeBaseIds":[1],"rewriteEnabled":false,"candidateTopK":10,"topK":5,"rerank":false}'
    body = substitute_request_body(body_fixture, context)

    spec = {
        "url": BASE_URL + target,
        "method": "POST",
        "headers": {"Accept": "text/event-stream"},
        "body": body,
        "content_type": "application/json",
        "auth_mode": "bearer_header",
        "auth_source": "BENCH_ACCESS_TOKEN",
        "expected_status": 200,
        "timeout_ms": 30000,
    }

    spec_path = run_set_dir / "smoke_spec.json"
    spec_path.write_text(json.dumps(spec, ensure_ascii=False, indent=2), encoding="utf-8")

    try:
        result = subprocess.run(
            [sys.executable, "-B", "scripts/benchmark/ttft_transport.py", "--spec", str(spec_path)],
            cwd=Path(__file__).resolve().parent.parent.parent,
            capture_output=True,
            text=True,
            timeout=45,
        )
    except subprocess.TimeoutExpired:
        print(json.dumps({"status": "FAIL", "phase": "ttft", "reason": "timeout"}, ensure_ascii=False), file=sys.stderr)
        return 1

    try:
        ttft = json.loads(result.stdout)
    except json.JSONDecodeError as e:
        print(json.dumps({"status": "FAIL", "phase": "ttft", "reason": f"invalid JSON: {e}", "stdout": result.stdout, "stderr": result.stderr}, ensure_ascii=False), file=sys.stderr)
        return 1

    # Validate minimal success criteria.
    checks = {
        "success": ttft.get("success") is True,
        "status_code_200": ttft.get("status_code") == 200,
        "t0_present": ttft.get("t0_monotonic") is not None,
        "t1_present": ttft.get("t1_monotonic") is not None,
        "ttft_present": ttft.get("ttft_ms") is not None,
        "ttft_positive": (ttft.get("ttft_ms") or 0) > 0,
    }

    if not all(checks.values()):
        print(json.dumps({
            "status": "FAIL",
            "phase": "ttft",
            "checks": checks,
            "result": ttft,
            "stderr": result.stderr,
        }, ensure_ascii=False, indent=2), file=sys.stderr)
        return 1

    print(json.dumps({
        "status": "PASS",
        "phase": "ttft",
        "run_set_id": RUN_SET_ID,
        "checks": checks,
        "ttft_ms": ttft["ttft_ms"],
        "result": ttft,
    }, ensure_ascii=False, indent=2))

    # Cleanup transient credentials.
    cred_path = run_set_dir / "runtime_credentials.json"
    if cred_path.exists():
        cred_path.unlink()

    # Best-effort resource cleanup; token remains valid until after this point.
    try:
        cleanup_run_set(scenario=SCENARIO, run_set_id=RUN_SET_ID, access_token=access_token)
    except Exception as e:
        print(json.dumps({"status": "WARN", "phase": "cleanup", "reason": str(e)}, ensure_ascii=False), file=sys.stderr)

    return 0


if __name__ == "__main__":
    sys.exit(main())
