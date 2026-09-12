#!/usr/bin/env python3
"""Target readiness preflight for Phase 8 Wave 2 benchmark execution.

Fails closed if any required dependency is unavailable. Does not execute the
benchmark itself.

Topology authority:
- backend internal actuator/health is the canonical backend readiness signal.
- Ollama is a host service at 127.0.0.1:11434 (not a Docker container).
- PostgreSQL is the Docker Compose service intellidesk-postgres, reachable on
  host port 5432 and via docker exec.
- Reranker gateway is a host service at 127.0.0.1:18182.
"""

import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path


def check_command(cmd: list[str], timeout: int = 10) -> tuple[bool, str]:
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout
        )
        return result.returncode == 0, ((result.stdout or "") + (result.stderr or "")).strip()
    except FileNotFoundError:
        return False, f"command not found: {cmd[0]}"
    except subprocess.TimeoutExpired:
        return False, "timeout"
    except OSError as e:
        return False, str(e)


def check_http(url: str, timeout: int = 5, method: str = "GET", data: bytes | None = None,
               headers: dict | None = None) -> tuple[bool, int | None, str]:
    try:
        req = urllib.request.Request(url, method=method, data=data, headers=headers or {}, unverifiable=True)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="replace")[:500]
            return True, resp.status, body
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")[:500]
        return True, e.code, body
    except Exception as e:
        return False, None, str(e)


def check_k6(k6_path: str = "k6") -> tuple[bool, str]:
    return check_command([k6_path, "version"])


def check_docker() -> tuple[bool, str]:
    return check_command(["docker", "--version"])


def check_docker_container(service: str) -> tuple[bool, str]:
    """Check that the named Docker Compose container is running.

    Services are started from deploy/docker-compose.yml (optionally with the
    e2e overlay). The container naming convention is intellidesk-<service>.
    """
    container_name = f"intellidesk-{service}"
    ok, out = check_command(["docker", "ps", "--filter", f"name={container_name}", "--format", "json"])
    if not ok:
        return False, out or f"docker ps failed for {container_name}"
    # docker ps --format json returns a JSON array/object per line when matches exist.
    return bool(out and out.strip()), out or f"container {container_name} not found"


def check_backend_internal_health() -> tuple[bool, int | None, str]:
    """Canonical backend readiness via internal actuator/health (Docker-only)."""
    ok, out = check_command([
        "docker", "exec", "intellidesk-backend",
        "wget", "-qO-", "http://localhost:8080/actuator/health"
    ], timeout=10)
    if not ok:
        return False, None, out or "docker exec backend actuator/health failed"
    try:
        payload = json.loads(out)
        status = payload.get("status", "UNKNOWN")
        return status == "UP", None, f"actuator/health status={status}"
    except json.JSONDecodeError:
        return False, None, f"non-JSON actuator response: {out[:200]}"


def check_backend_external_reachability() -> tuple[bool, int | None, str]:
    """External reachability cross-check via a permitAll endpoint.

    /api/auth/login returns 400 for invalid payload, which proves Nginx + backend
    are responding. 401/403/404/500 are also accepted as "reachable" because they
    are valid HTTP responses from the stack; only transport errors fail.
    """
    data = json.dumps({"email": "preflight@intellidesk.local", "password": "preflight"}).encode("utf-8")
    ok, status, detail = check_http(
        "http://127.0.0.1:80/api/auth/login",
        method="POST",
        data=data,
        headers={"Content-Type": "application/json"},
        timeout=5,
    )
    if not ok:
        return False, status, detail
    # Any HTTP response from the backend/Nginx proves reachability.
    return True, status, detail


def _postgres_user() -> str:
    return os.environ.get("POSTGRES_USER", "postgres")


def check_postgres() -> tuple[bool, str]:
    user = _postgres_user()
    db = os.environ.get("POSTGRES_DB", "intellidesk")
    ok, out = check_command([
        "docker", "exec", "intellidesk-postgres",
        "psql", "-U", user, "-d", db, "-c", "SELECT 1;"
    ])
    if ok:
        return True, out
    # Fallback: host port (requires local psql).
    ok2, out2 = check_command([
        "psql", "-h", "localhost", "-p", "5432", "-U", user, "-d", db, "-c", "SELECT 1;"
    ])
    if ok2:
        return True, out2
    return False, f"docker exec: {out}; host psql: {out2}"


def check_ollama_tags() -> tuple[bool, str]:
    try:
        req = urllib.request.Request("http://127.0.0.1:11434/api/tags", method="GET")
        with urllib.request.urlopen(req, timeout=5) as resp:
            detail = resp.read().decode("utf-8", errors="replace")
            if resp.status != 200:
                return False, f"status={resp.status}, detail={detail[:500]}"
            payload = json.loads(detail)
            models = [m.get("name", "") for m in payload.get("models", [])]
            return True, json.dumps(models, ensure_ascii=False)
    except Exception as e:
        return False, str(e)


def check_ollama_model(model: str) -> tuple[bool, str]:
    ok, detail = check_ollama_tags()
    if not ok:
        return False, detail
    return model in detail, detail


def check_ollama_native_dimension(model: str) -> tuple[bool, int | None, str]:
    """Informational check of the native Ollama /api/embeddings dimension.

    This does NOT define the project contract; it is recorded for transparency.
    """
    try:
        req = urllib.request.Request(
            "http://127.0.0.1:11434/api/embeddings",
            method="POST",
            data=json.dumps({"model": model, "prompt": "preflight dimension check"}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
            embedding = payload.get("embedding", [])
            dimension = len(embedding) if isinstance(embedding, list) else None
            return True, dimension, f"native dimension={dimension}"
    except Exception as e:
        return False, None, str(e)


def check_ollama_embedding_contract(model: str, requested_dimensions: int) -> dict:
    """Verify the production-compatible embedding contract.

    The project uses OpenAI-compatible /v1/embeddings with explicit dimensions.
    Ollama exposes this at POST /v1/embeddings. The request must include
    `dimensions` to match the project contract (e.g. 1536).
    """
    result = {
        "provider": "Ollama",
        "compatibility_api": "/v1/embeddings",
        "model": model,
        "requested_dimensions": requested_dimensions,
        "returned_dimensions": None,
        "returned_model": None,
        "dimension_matches_plan": False,
        "model_matches_plan": False,
        "http_ok": False,
        "detail": None,
    }
    try:
        req = urllib.request.Request(
            "http://127.0.0.1:11434/v1/embeddings",
            method="POST",
            data=json.dumps({
                "model": model,
                "input": "preflight contract check",
                "dimensions": requested_dimensions,
            }).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
            result["http_ok"] = resp.status == 200
            result["returned_model"] = payload.get("model")
            data = payload.get("data", [])
            if isinstance(data, list) and len(data) > 0:
                embedding = data[0].get("embedding", [])
                result["returned_dimensions"] = len(embedding) if isinstance(embedding, list) else None
                if result["returned_model"] is None:
                    result["returned_model"] = data[0].get("object") or data[0].get("model")
            elif "embedding" in payload:
                # Some /v1/embeddings implementations return a single embedding.
                embedding = payload.get("embedding", [])
                result["returned_dimensions"] = len(embedding) if isinstance(embedding, list) else None
            result["dimension_matches_plan"] = result["returned_dimensions"] == requested_dimensions
            result["model_matches_plan"] = result["returned_model"] == model
            result["detail"] = (
                f"status={resp.status}; "
                f"returned_model={result['returned_model']}; "
                f"returned_dimensions={result['returned_dimensions']}"
            )
    except Exception as e:
        result["detail"] = str(e)
    return result


def check_reranker_gateway() -> tuple[bool, int | None, str]:
    return check_http("http://127.0.0.1:18182/health", timeout=3)


def check_reranker_model_identity() -> tuple[bool, str]:
    ok, status, detail = check_reranker_gateway()
    if not ok or status != 200:
        return False, f"gateway unreachable: status={status}, detail={detail}"
    try:
        payload = json.loads(detail)
        model_id = payload.get("model_id", "")
        return model_id == "BAAI/bge-reranker-v2-m3", detail
    except json.JSONDecodeError:
        return False, f"non-JSON gateway response: {detail[:200]}"


_SECRET_VALUE_PATTERNS = ("bearer ", "sk-", "ak-")
_SECRET_KEY_PATTERNS = ("api_key", "apikey", "access_token", "authorization",
                        "password", "secret", "private_key", "credential")


def _resolve_auth_source(source: str | None) -> tuple[bool, str | None]:
    """Resolve an auth source to a credential without exposing it.

    Returns (available, credential).  credential is returned only so the caller
    can verify it is not accidentally written into the config; it is never logged.
    """
    if not source:
        return False, None
    if os.path.sep in source or "/" in source:
        path = Path(source)
        if path.exists():
            try:
                return True, path.read_text(encoding="utf-8").strip()
            except Exception:
                return False, None
        return False, None
    value = os.environ.get(source)
    if value:
        return True, value
    return False, None


def check_auth_readiness(config: dict, tooling_only: bool = False) -> dict:
    """Verify that an authenticated scenario has a safe, available credential source.

    Returns dict with:
      - ok: True if auth strategy is defined, source is available, and no secret leaks.
      - auth_mode: configured auth mode (or "none").
      - auth_source: configured credential source identifier.
      - strategy_defined: True when auth_mode is supported and auth_source is set.
      - source_available: True when the env var / file exists and is non-empty.
      - no_secret_leak: True when the resolved credential does not appear in config.
      - detail: human-readable status (contains no credential value).

    If tooling_only is True, source availability is not required; only the auth
    strategy identity and secret-leak checks are enforced. This is used for Step 3
    preparation preflight before per-run-set provisioning populates the env vars.
    """
    auth_mode = config.get("auth_mode", "none")
    auth_source = config.get("auth_source")
    result = {
        "ok": False,
        "auth_mode": auth_mode,
        "auth_source": auth_source,
        "strategy_defined": False,
        "source_available": False,
        "no_secret_leak": False,
        "detail": None,
    }

    if auth_mode == "none":
        result["ok"] = True
        result["strategy_defined"] = True
        result["source_available"] = True
        result["no_secret_leak"] = True
        result["detail"] = "no auth required"
        return result

    supported = ("session_cookie", "api_key_header", "bearer_header")
    if auth_mode not in supported:
        result["detail"] = f"unsupported auth_mode: {auth_mode}"
        return result

    if not auth_source:
        result["detail"] = "auth_source missing"
        return result

    result["strategy_defined"] = True

    # v1.2 formal credential source identities.
    allowed_sources = {"BENCH_ACCESS_TOKEN", "BENCH_API_KEY_SECRET"}
    if auth_source not in allowed_sources:
        result["detail"] = f"credential source not in v1.2 allowlist: {auth_source}"
        return result

    available, credential = _resolve_auth_source(auth_source)
    result["source_available"] = available

    canonical_config = json.dumps(config, sort_keys=True, ensure_ascii=False).lower()
    leak = False
    if credential:
        lowered = credential.lower()
        for pattern in _SECRET_VALUE_PATTERNS:
            if pattern in canonical_config:
                leak = True
                break
        # The literal credential value must not appear in the frozen config.
        if lowered and lowered in canonical_config:
            leak = True
    else:
        # No credential resolved; leak check is vacuously true.
        pass

    # Also forbid secret-like keys anywhere in the frozen config.
    for key in _SECRET_KEY_PATTERNS:
        if f'"{key}"' in canonical_config:
            leak = True
            break

    result["no_secret_leak"] = not leak

    if tooling_only:
        if result["no_secret_leak"]:
            result["ok"] = True
            result["detail"] = "auth strategy and source identity defined; no secret leak (tooling readiness)"
        else:
            result["detail"] = "suspected secret leak in frozen config"
        return result

    if result["source_available"] and result["no_secret_leak"]:
        result["ok"] = True
        result["detail"] = "auth strategy defined, credential source available, no secret leak detected"
    elif not result["source_available"]:
        result["detail"] = f"credential source not available: {auth_source}"
    else:
        result["detail"] = "suspected secret leak in frozen config"

    return result


def check_target_reachability(target: str, expected_status: int, method: str = "GET",
                               data: bytes | None = None) -> dict:
    """Check target endpoint reachability and classify auth semantics.

    Returns a dict with:
      - ok: True if the endpoint responded at the HTTP layer.
      - reachable: True if the endpoint responded at the HTTP layer.
      - auth_required: True if the response was 401 (expected for protected endpoints).
      - status: HTTP status code.
      - expected_status: configured expected status.
      - detail: response body / error message.
    """
    # v1.2: logical target templates with placeholders are not directly reachable
    # during preparation; they are validated as topology-defined.
    if "{" in target and "}" in target:
        return {
            "ok": True,
            "reachable": True,
            "auth_required": False,
            "status": None,
            "expected_status": expected_status,
            "detail": "logical target template; runtime substitution required before direct reachability check",
        }

    if target.startswith("/"):
        target = "http://127.0.0.1:80" + target

    ok, status, detail = check_http(target, method=method, data=data, timeout=5)
    if not ok:
        return {
            "ok": False,
            "reachable": False,
            "auth_required": False,
            "status": status,
            "expected_status": expected_status,
            "detail": detail,
        }
    auth_required = status == 401
    # For Step 3 preparation, reachability is sufficient. A 401 on a protected
    # target is recorded as auth_required rather than target_ready.
    return {
        "ok": True,
        "reachable": True,
        "auth_required": auth_required,
        "status": status,
        "expected_status": expected_status,
        "detail": detail,
    }


def check_helper_readiness(bind: str = "127.0.0.1", port: int = 18181,
                           timeout_s: float = 30.0) -> dict:
    """Start the TTFT helper temporarily and verify its readiness probe.

    This validates the formal helper lifecycle without executing a benchmark.
    The helper is started on the formal bind/port, polled for /health, then shut
    down. Returns a dict with ok, detail, and startup_time_ms.
    """
    import time as _time
    from ttft_transport import run_server, FORMAL_HELPER_PORT

    if port != FORMAL_HELPER_PORT:
        return {
            "ok": False,
            "detail": f"formal helper port mismatch: expected {FORMAL_HELPER_PORT}, got {port}",
            "startup_time_ms": None,
        }

    server = None
    start = _time.monotonic()
    try:
        server, _ = run_server(port)
        url = f"http://{bind}:{port}/health"
        ready = False
        deadline = _time.monotonic() + timeout_s
        while _time.monotonic() < deadline:
            try:
                req = urllib.request.Request(url, method="GET")
                with urllib.request.urlopen(req, timeout=2) as resp:
                    if resp.status == 200:
                        body = json.loads(resp.read().decode("utf-8"))
                        if body.get("status") == "ready":
                            ready = True
                            break
            except Exception:
                pass
            _time.sleep(0.2)
        startup_ms = (_time.monotonic() - start) * 1000.0
        if ready:
            return {
                "ok": True,
                "detail": f"helper ready on {bind}:{port} within {startup_ms:.0f}ms",
                "startup_time_ms": startup_ms,
            }
        return {
            "ok": False,
            "detail": f"helper readiness probe failed within {timeout_s}s",
            "startup_time_ms": startup_ms,
        }
    except Exception as e:
        return {
            "ok": False,
            "detail": f"helper startup failed: {e}",
            "startup_time_ms": (_time.monotonic() - start) * 1000.0,
        }
    finally:
        if server is not None:
            try:
                server.shutdown()
                server.server_close()
            except Exception:
                pass


def run_preflight(config: dict | None = None, k6_path: str = "k6",
                  tooling_only: bool = False) -> dict:
    results: dict[str, dict] = {}

    target = config.get("target") if config else "http://127.0.0.1:80/api/health"
    expected_status = config.get("expected_status") if config else 200
    target_method = config.get("http_method", "GET") if config else "GET"

    # k6 is mandatory for external benchmark scenarios.
    k6_ok, k6_detail = check_k6(k6_path)
    results["k6_available"] = {"ok": k6_ok, "detail": k6_detail}

    docker_ok, docker_detail = check_docker()
    results["docker_available"] = {"ok": docker_ok, "detail": docker_detail}

    # Canonical backend readiness: internal actuator/health.
    backend_health_ok, _, backend_health_detail = check_backend_internal_health()
    results["backend_internal_health"] = {
        "ok": backend_health_ok,
        "detail": backend_health_detail,
    }

    # External reachability cross-check.
    backend_ext_ok, backend_ext_status, backend_ext_detail = check_backend_external_reachability()
    results["backend_external_reachable"] = {
        "ok": backend_ext_ok,
        "status": backend_ext_status,
        "detail": backend_ext_detail,
    }

    # Benchmark target endpoint (reachability + auth semantics).
    target_result = check_target_reachability(target, expected_status, method=target_method)
    results["target_reachable"] = target_result

    # Auth readiness: strategy defined, credential source available, no secret leak.
    # v1.2: tooling-only mode validates strategy and leak boundaries without requiring
    # actual per-run-set credentials (used during Step 3 preparation preflight).
    auth_readiness = check_auth_readiness(config or {}, tooling_only=tooling_only)
    results["auth_readiness"] = auth_readiness

    # Required Docker services (from base docker-compose.yml).
    for service in ("postgres", "redis", "rabbitmq", "minio", "elasticsearch"):
        svc_ok, svc_detail = check_docker_container(service)
        results[f"docker_service_{service}_healthy"] = {"ok": svc_ok, "detail": svc_detail}

    # Backend container specifically.
    backend_svc_ok, backend_svc_detail = check_docker_container("backend")
    results["docker_service_backend_healthy"] = {"ok": backend_svc_ok, "detail": backend_svc_detail}

    # Elasticsearch health.
    es_ok, es_status, es_detail = check_http("http://127.0.0.1:9200/_cluster/health", timeout=3)
    results["elasticsearch_healthy"] = {
        "ok": es_ok and (es_status == 200),
        "status": es_status,
        "detail": es_detail,
    }

    # PostgreSQL reachability.
    pg_ok, pg_detail = check_postgres()
    results["postgres_reachable"] = {"ok": pg_ok, "detail": pg_detail}

    # Ollama host service reachability.
    ollama_tags_ok, ollama_tags_detail = check_ollama_tags()
    results["ollama_reachable"] = {"ok": ollama_tags_ok, "detail": ollama_tags_detail}

    # Embedding model availability.
    emb_ok, emb_detail = check_ollama_model("qwen3-embedding:8b")
    results["embedding_model_available"] = {
        "ok": emb_ok,
        "expected_model": "qwen3-embedding:8b",
        "detail": emb_detail,
    }

    # Embedding contract: production-compatible /v1/embeddings with explicit dimensions.
    expected_dimension = int(os.environ.get("EMBEDDING_DIMENSION", "1536"))
    contract = check_ollama_embedding_contract("qwen3-embedding:8b", expected_dimension)
    results["embedding_contract"] = {
        "ok": contract["http_ok"] and contract["dimension_matches_plan"] and contract["model_matches_plan"],
        "provider": contract["provider"],
        "compatibility_api": contract["compatibility_api"],
        "model": contract["model"],
        "requested_dimensions": contract["requested_dimensions"],
        "returned_dimensions": contract["returned_dimensions"],
        "returned_model": contract["returned_model"],
        "dimension_matches_plan": contract["dimension_matches_plan"],
        "model_matches_plan": contract["model_matches_plan"],
        "detail": contract["detail"],
    }

    # Native dimension is informational only; it does not define the project contract.
    native_ok, native_dim, native_detail = check_ollama_native_dimension("qwen3-embedding:8b")
    results["embedding_native_dimension"] = {
        "ok": native_ok and native_dim is not None,
        "native_dimension": native_dim,
        "detail": native_detail,
    }

    # Reranker gateway reachable.
    rg_ok, rg_status, rg_detail = check_reranker_gateway()
    results["reranker_gateway_reachable"] = {
        "ok": rg_ok and rg_status == 200,
        "status": rg_status,
        "detail": rg_detail,
    }

    # Reranker model identity.
    rr_ok, rr_detail = check_reranker_model_identity()
    results["reranker_model_identity"] = {
        "ok": rr_ok,
        "expected_model": "BAAI/bge-reranker-v2-m3",
        "detail": rr_detail,
    }

    # TTFT helper readiness: validate formal lifecycle (bind 127.0.0.1, port 18181,
    # /health readiness, startup within 30s, graceful shutdown).
    helper_result = check_helper_readiness()
    results["ttft_helper_readiness"] = helper_result

    # Overall PASS if all mandatory infra checks pass. target_reachable must be
    # reachable; auth_required is recorded but does not block preparation.
    mandatory_checks = [
        results["k6_available"]["ok"],
        results["docker_available"]["ok"],
        results["backend_internal_health"]["ok"],
        results["backend_external_reachable"]["ok"],
        results["target_reachable"]["reachable"],
        results["auth_readiness"]["ok"],
        results["docker_service_postgres_healthy"]["ok"],
        results["docker_service_redis_healthy"]["ok"],
        results["docker_service_rabbitmq_healthy"]["ok"],
        results["docker_service_minio_healthy"]["ok"],
        results["docker_service_elasticsearch_healthy"]["ok"],
        results["docker_service_backend_healthy"]["ok"],
        results["elasticsearch_healthy"]["ok"],
        results["postgres_reachable"]["ok"],
        results["ollama_reachable"]["ok"],
        results["embedding_model_available"]["ok"],
        results["embedding_contract"]["ok"],
        results["reranker_gateway_reachable"]["ok"],
        results["reranker_model_identity"]["ok"],
        results["ttft_helper_readiness"]["ok"],
    ]
    overall = all(mandatory_checks)
    return {"status": "PASS" if overall else "FAIL", "checks": results}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Target readiness preflight")
    parser.add_argument("--config", help="Frozen benchmark config JSON")
    parser.add_argument("--output", "-o", help="Output JSON file")
    parser.add_argument("--k6-path", default=os.environ.get("K6_PATH", "k6"), help="k6 binary path")
    parser.add_argument("--tooling-only", action="store_true",
                        help="Step 3 preparation mode: validate auth strategy and secret boundary without requiring credentials")
    args = parser.parse_args(argv)

    config: dict = {}
    if args.config:
        perf = json.loads(Path(args.config).read_text(encoding="utf-8")).get("performance_config", {})
        config.update(perf)

    result = run_preflight(config, k6_path=args.k6_path, tooling_only=args.tooling_only)
    print(json.dumps(result, ensure_ascii=False, indent=2))

    if args.output:
        Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
