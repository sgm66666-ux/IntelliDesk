#!/usr/bin/env python3
"""SSE TTFT transport helper for Phase 8 Wave 2 benchmark harness.

k6 v2.2.0 core does not expose incremental response-body streaming, so this
evaluation-only helper sits between k6 and the backend:

  k6 --(HTTP JSON)--> helper --> localhost:80 --> Nginx --> backend SSE stream

The helper:
- resolves auth credentials from outside the repo (env var / file), never from
  the benchmark config;
- makes the real HTTP/SSE request through the external Nginx path;
- parses the SSE stream incrementally;
- records t0 (monotonic timestamp after request dispatch/write completion) and
  t1 (first qualifying token event);
- returns a JSON result that k6 consumes and tags onto the raw sample.

The helper can run as a one-shot CLI or as a threaded HTTP server.
"""

import argparse
import http.client
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from typing import Any


class _IncompleteReadHelper:
    """Compatibility shim for http.client.IncompleteRead.

    Older Python versions expose partial bytes via the `partial` attribute;
    newer versions may not. This helper normalizes access.
    """

    @staticmethod
    def partial_bytes(exc: Exception) -> bytes | None:
        return getattr(exc, "partial", None)


QUALIFYING_EVENT_TYPE = "token"

# v1.2: only these credential source identities are allowed. The helper reads
# the actual secret value from its own process environment using the source as
# the env-var name. Any other value is rejected.
ALLOWED_CREDENTIAL_SOURCES = {"BENCH_ACCESS_TOKEN", "BENCH_API_KEY_SECRET"}
FORMAL_HELPER_PORT = 18181


def resolve_credential(source: str | None) -> str | None:
    """Resolve a credential from an allowed env-var source.

    Returns None if the source is missing/empty, not in the allowlist, or the
    credential cannot be read. The credential value is never read from a file
    path (k6 must not send the secret itself).
    """
    if not source:
        return None
    if source not in ALLOWED_CREDENTIAL_SOURCES:
        return None
    return os.environ.get(source)


def build_headers(spec: dict) -> dict:
    """Build request headers from the measure spec, injecting auth if configured."""
    headers = dict(spec.get("headers", {}))
    content_type = spec.get("content_type")
    if content_type:
        headers["Content-Type"] = content_type

    auth_mode = spec.get("auth_mode", "none")
    auth_source = spec.get("auth_source")
    if auth_mode != "none" and auth_source:
        credential = resolve_credential(auth_source)
        if credential:
            if auth_mode == "api_key_header":
                headers["X-API-Key"] = credential
            elif auth_mode == "bearer_header":
                headers["Authorization"] = f"Bearer {credential}"
            elif auth_mode == "session_cookie":
                headers["Cookie"] = credential
    return headers


def parse_sse_events(data: str) -> list[dict]:
    """Parse a block of SSE text into completed events.

    Each event is a dict with optional fields: event, id, retry, data (str).
    """
    events = []
    current = {}
    data_lines = []

    for raw_line in data.splitlines(True):
        line = raw_line.rstrip("\r\n")
        if line == "":
            # Event boundary.
            if data_lines or current:
                current["data"] = "\n".join(data_lines)
                events.append(current)
            current = {}
            data_lines = []
            continue

        if line.startswith(":"):
            # Comment line; ignore.
            continue

        if ":" in line:
            field, value = line.split(":", 1)
            if value.startswith(" "):
                value = value[1:]
        else:
            field = line
            value = ""

        if field == "event":
            current["event"] = value
        elif field == "id":
            current["id"] = value
        elif field == "retry":
            current["retry"] = value
        elif field == "data":
            data_lines.append(value)
        # Unknown fields are ignored.

    # Trailing event without a blank line: still emit if it has data or metadata.
    if data_lines or current:
        current["data"] = "\n".join(data_lines)
        events.append(current)

    return events


def _redact_secrets(value: Any) -> Any:
    """Recursively redact strings that look like credentials."""
    if isinstance(value, dict):
        return {k: _redact_secrets(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_redact_secrets(v) for v in value]
    if isinstance(value, str):
        lowered = value.lower()
        if lowered.startswith("bearer "):
            return "[REDACTED_BEARER]"
        if lowered.startswith("isk_"):
            return "[REDACTED_API_KEY]"
        if any(lowered.startswith(p) for p in ("sk-", "ak-")):
            return "[REDACTED_SECRET]"
    return value


def is_qualifying_token(event: dict) -> tuple[bool, str | None]:
    """Return (is_qualifying, delta) for an SSE event.

    Qualifying event: event type == 'token' and delta is a non-empty string.
    """
    event_type = event.get("event", "message")
    if event_type != QUALIFYING_EVENT_TYPE:
        return False, None
    data = event.get("data", "")
    if not data:
        return False, None
    try:
        payload = json.loads(data)
    except json.JSONDecodeError:
        return False, None
    if not isinstance(payload, dict):
        return False, None
    delta = payload.get("delta")
    if delta is None:
        return False, None
    delta_str = str(delta).strip()
    if delta_str == "":
        return False, None
    return True, delta_str


def measure_sse_ttft(spec: dict) -> dict:
    """Measure one SSE request and return a result dict.

    Fields:
      - status_code: HTTP status code
      - success: True if status_code == expected and a qualifying token was found
      - latency_ms: elapsed milliseconds to the first qualifying token on success
      - t0_monotonic: monotonic timestamp after request dispatch/write completion
      - t1_monotonic: monotonic timestamp of the first qualifying token event
      - ttft_ms: t1 - t0 (None if no qualifying token)
      - qualifying_event_type: 'token' if a qualifying event was found, else None
      - error: error message if success is False
    """
    url = spec["url"]
    method = spec.get("method", "GET").upper()
    body = spec.get("body")
    timeout_ms = int(spec.get("timeout_ms", 30000))
    timeout_s = timeout_ms / 1000.0
    expected_status = int(spec.get("expected_status", 200))
    headers = build_headers(spec)

    body_bytes = None
    if body is not None:
        if isinstance(body, (dict, list)):
            body_bytes = json.dumps(body).encode("utf-8")
        else:
            body_bytes = str(body).encode("utf-8")
        if "Content-Length" not in headers and body_bytes:
            headers["Content-Length"] = str(len(body_bytes))

    result = {
        "status_code": None,
        "success": False,
        "latency_ms": None,
        "t0_monotonic": None,
        "t1_monotonic": None,
        "ttft_ms": None,
        "qualifying_event_type": None,
        "error": None,
    }

    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https"):
        result["error"] = f"unsupported scheme: {parsed.scheme}"
        return result

    try:
        if parsed.scheme == "https":
            conn = http.client.HTTPSConnection(parsed.hostname, parsed.port or 443, timeout=timeout_s)
        else:
            conn = http.client.HTTPConnection(parsed.hostname, parsed.port or 80, timeout=timeout_s)

        path = parsed.path or "/"
        if parsed.query:
            path += "?" + parsed.query

        conn.putrequest(method, path)
        for key, val in headers.items():
            conn.putheader(key, val)
        conn.endheaders(message_body=body_bytes)

        # t0: request dispatch/write completion, client starts waiting for stream.
        t0 = time.monotonic()
        result["t0_monotonic"] = t0

        response = conn.getresponse()
        result["status_code"] = response.status

        if response.status != expected_status:
            result["error"] = f"status={response.status}, expected={expected_status}"
            try:
                response.read()
            except Exception:
                pass
            conn.close()
            result["latency_ms"] = (time.monotonic() - t0) * 1000.0
            return result

        # Read complete SSE lines as they arrive. HTTPResponse.read(n) waits for
        # n bytes or EOF and therefore cannot measure a long-lived stream's
        # first token. Accumulate one event through its terminating blank line.
        event_buffer = b""
        t1 = None
        qualifying_type = None
        read_start = time.monotonic()
        remaining_timeout = timeout_s

        def _parse_event(buf: bytes) -> bool:
            """Parse one complete SSE event and identify the first real token."""
            nonlocal t1, qualifying_type
            if not buf:
                return False
            text = buf.decode("utf-8", errors="replace")
            events = parse_sse_events(text)
            for event in events:
                is_qual, _ = is_qualifying_token(event)
                if is_qual:
                    t1 = time.monotonic()
                    qualifying_type = QUALIFYING_EVENT_TYPE
                    return True
            return False

        while remaining_timeout > 0:
            try:
                if conn.sock is not None:
                    conn.sock.settimeout(max(0.001, remaining_timeout))
                line = response.readline()
            except http.client.IncompleteRead as e:
                line = _IncompleteReadHelper.partial_bytes(e)
                result["error"] = f"IncompleteRead: {e}"
            except Exception as e:
                result["error"] = f"response read error: {e}"
                break

            if line:
                event_buffer += line
                if line in (b"\n", b"\r\n"):
                    if _parse_event(event_buffer):
                        event_buffer = b""
                        break
                    event_buffer = b""
            else:
                if event_buffer and _parse_event(event_buffer):
                    break
                break

            remaining_timeout = timeout_s - (time.monotonic() - read_start)

        t_end = time.monotonic()
        result["latency_ms"] = (t_end - t0) * 1000.0

        if t1 is not None:
            result["t1_monotonic"] = t1
            result["ttft_ms"] = (t1 - t0) * 1000.0
            result["latency_ms"] = result["ttft_ms"]
            result["qualifying_event_type"] = qualifying_type
            result["success"] = True
        else:
            result["error"] = result["error"] or "no qualifying token event before EOF/timeout"

        conn.close()
    except Exception as e:
        result["error"] = f"request failed: {e}"
        if result["t0_monotonic"] is not None:
            result["latency_ms"] = (time.monotonic() - result["t0_monotonic"]) * 1000.0

    return result


class MeasureHandler(BaseHTTPRequestHandler):
    """HTTP handler for the TTFT helper server."""

    def log_message(self, fmt, *args):
        # Suppress server logs to avoid leaking secrets or cluttering output.
        pass

    def _send_json(self, status: int, body: dict) -> None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
            # The caller may enforce a stricter timeout. Do not turn a closed
            # response socket into noisy helper-server tracebacks.
            pass

    def do_GET(self):
        if self.path == "/health":
            self._send_json(200, {"status": "ready"})
            return
        self._send_json(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/measure":
            content_length = int(self.headers.get("Content-Length", 0))
            if content_length <= 0:
                self._send_json(400, {"error": "empty body"})
                return

            try:
                payload = json.loads(self.rfile.read(content_length).decode("utf-8"))
            except json.JSONDecodeError as e:
                self._send_json(400, {"error": f"invalid JSON: {e}"})
                return

            # v1.2: fail-closed if the measure spec itself contains a raw
            # credential value instead of an allowed source identity.
            auth_source = payload.get("auth_source")
            if auth_source and auth_source not in ALLOWED_CREDENTIAL_SOURCES:
                self._send_json(400, {"error": "credential source not allowed"})
                return

            result = measure_sse_ttft(payload)

            # Defensive redaction: ensure no secret-like values leak in the
            # JSON response (e.g. from headers or body fields).
            result = _redact_secrets(result)
            self._send_json(200, result)
            return
        self._send_json(404, {"error": "not found"})


class BenchmarkThreadingHTTPServer(ThreadingHTTPServer):
    """Threaded localhost helper with a backlog sized above formal VU10 bursts."""

    request_queue_size = 128


def run_server(port: int) -> tuple[ThreadingHTTPServer, Thread]:
    server = BenchmarkThreadingHTTPServer(("127.0.0.1", port), MeasureHandler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="SSE TTFT transport helper")
    parser.add_argument("--server", action="store_true", help="Run as HTTP server")
    parser.add_argument("--port", type=int, default=18181, help="Server port (default 18181)")
    parser.add_argument("--spec", help="Path to JSON measure spec (one-shot mode)")
    args = parser.parse_args(argv)

    if args.server:
        server, _ = run_server(args.port)
        print(json.dumps({"status": "listening", "port": args.port}, ensure_ascii=False), flush=True)
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            server.shutdown()
        return 0

    if not args.spec:
        print("--spec required in one-shot mode", file=sys.stderr)
        return 1

    spec = json.loads(Path(args.spec).read_text(encoding="utf-8"))
    result = measure_sse_ttft(spec)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["success"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
