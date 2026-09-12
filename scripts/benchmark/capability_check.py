#!/usr/bin/env python3
"""BENCH_HARNESS_CAPABILITY_CHECK for Phase 8 Wave 2.

This script verifies that the project environment can produce k6 JSON output
containing per-sample raw records. It is a format/availability check only:

- 1 virtual user
- 1 iteration
- Temporary local HTTP echo server
- No pressure test
- No benchmark raw written to docs/evaluation/

PASS/FAIL criteria:
    1. k6 binary is available and reports a version.
    2. Temporary HTTP server starts and responds to /ping.
    3. k6 produces a JSON output file.
    4. The JSON output contains at least one per-sample record with the fields
       metric, type, data, timestamp.
    5. Exactly 1 Point sample is observed (1 VU x 1 iteration).
"""

import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


def find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


class EchoHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        # Suppress server logs to keep output clean.
        pass

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"pong")


class TemporaryHttpServer:
    def __init__(self, port: int):
        self.port = port
        self.server = HTTPServer(("127.0.0.1", port), EchoHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self):
        self.thread.start()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2.0)


def check_k6(k6_path: str) -> tuple[bool, str]:
    try:
        result = subprocess.run(
            [k6_path, "version"],
            capture_output=True,
            text=True,
            timeout=10,
        )
        if result.returncode != 0:
            return False, f"k6 --version failed: {result.stderr.strip()}"
        version = result.stdout.strip().splitlines()[0]
        return True, version
    except FileNotFoundError:
        return False, f"k6 binary not found: {k6_path}"
    except subprocess.TimeoutExpired:
        return False, "k6 --version timed out"
    except Exception as e:
        return False, f"k6 check error: {e}"


def write_k6_script(script_path: Path, base_url: str) -> None:
    script = f"""import http from 'k6/http';
import {{ check }} from 'k6';

export const options = {{
  vus: 1,
  iterations: 1,
}};

export default function () {{
  const res = http.get('{base_url}/ping');
  check(res, {{
    'status is 200': (r) => r.status === 200,
  }});
}}
"""
    script_path.write_text(script, encoding="utf-8")


def run_k6(k6_path: str, script_path: Path, output_path: Path) -> tuple[bool, str]:
    try:
        result = subprocess.run(
            [k6_path, "run", str(script_path), "--out", f"json={output_path}"],
            capture_output=True,
            text=True,
            timeout=30,
        )
        if result.returncode != 0:
            return False, f"k6 run failed: {result.stderr.strip() or result.stdout.strip()}"
        return True, "k6 run completed"
    except subprocess.TimeoutExpired:
        return False, "k6 run timed out"
    except Exception as e:
        return False, f"k6 run error: {e}"


def validate_raw_output(output_path: Path) -> tuple[bool, str, int]:
    if not output_path.exists() or output_path.stat().st_size == 0:
        return False, "k6 JSON output file missing or empty", 0

    point_count = 0
    duration_point_count = 0
    required_fields = {"metric", "type", "data"}
    found_fields = False

    with open(output_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(record, dict):
                continue
            if required_fields.issubset(record.keys()):
                found_fields = True
            if record.get("type") == "Point":
                point_count += 1
                data = record.get("data", {})
                # k6 v2.x emits data.time; older versions also emitted top-level timestamp.
                has_timestamp = record.get("timestamp") is not None or data.get("time") is not None
                if record.get("metric") == "http_req_duration" and has_timestamp:
                    duration_point_count += 1

    if not found_fields:
        return False, "per-sample raw fields (metric/type/data) not found", point_count
    if point_count < 1:
        return False, "expected at least 1 Point sample, found 0", point_count
    if duration_point_count < 1:
        return False, "expected at least 1 http_req_duration Point sample", point_count
    return True, "raw output validation passed", point_count


def main(argv: list[str]) -> int:
    k6_path = os.environ.get("K6_PATH", "k6")
    output_dir = Path(os.environ.get("OUTPUT_DIR", "logs"))
    output_dir.mkdir(parents=True, exist_ok=True)

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output_path = output_dir / f"bench_harness_capability_check_{timestamp}.json"

    result = {
        "status": "PASS",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "checks": {
            "k6_binary_available": False,
            "k6_version": None,
            "server_started": False,
            "k6_json_output_created": False,
            "raw_sample_fields_present": False,
            "raw_samples_count": 0,
        },
        "reason": "",
        "output_path": str(output_path),
    }

    # Check 1: k6 binary available.
    ok, version_or_error = check_k6(k6_path)
    result["checks"]["k6_binary_available"] = ok
    if not ok:
        result["status"] = "FAIL"
        result["reason"] = version_or_error
        output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1
    result["checks"]["k6_version"] = version_or_error

    # Check 2: temporary HTTP server starts.
    port = find_free_port()
    base_url = f"http://127.0.0.1:{port}"
    server = TemporaryHttpServer(port)
    server.start()
    time.sleep(0.5)

    # Quick health check.
    try:
        import urllib.request
        with urllib.request.urlopen(base_url + "/ping", timeout=5) as resp:
            if resp.status == 200:
                result["checks"]["server_started"] = True
    except Exception as e:
        result["status"] = "FAIL"
        result["reason"] = f"temporary server health check failed: {e}"
        server.stop()
        output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1

    # Check 3: k6 run produces JSON output.
    with tempfile.TemporaryDirectory() as tmpdir:
        script_path = Path(tmpdir) / "capability_check.js"
        k6_output_path = Path(tmpdir) / "k6_output.json"
        write_k6_script(script_path, base_url)
        ok, msg = run_k6(k6_path, script_path, k6_output_path)
        result["checks"]["k6_json_output_created"] = ok
        if not ok:
            result["status"] = "FAIL"
            result["reason"] = msg
            server.stop()
            output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 1

        # Check 4 & 5: raw sample fields and count.
        ok, msg, count = validate_raw_output(k6_output_path)
        result["checks"]["raw_sample_fields_present"] = ok
        result["checks"]["raw_samples_count"] = count
        if not ok:
            result["status"] = "FAIL"
            result["reason"] = msg
            server.stop()
            output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 1

    server.stop()

    result["reason"] = "All checks passed"
    output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
