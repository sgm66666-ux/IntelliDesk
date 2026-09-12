#!/usr/bin/env python3
"""Deterministic benchmark credential/resource provisioning for Phase 8 Wave 2.

Implements the v1.2-amendment PRE_BENCH_PROVISIONING procedure:

1. fresh benchmark user
2. login -> access token
3. inject BENCH_ACCESS_TOKEN into runner env
4. fresh workspace
5. fresh knowledge base
6. upload deterministic fixture and wait COMPLETED
7. fresh conversation
8. fresh API key -> inject BENCH_API_KEY_SECRET
9. Bearer auth smoke
10. API-Key auth smoke
11. write runtime_context atomically

All resources are disposable per run-set. Reuse/select/fallback is prohibited.
"""

import json
import os
import ssl
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4

from runtime_context import build_runtime_context, write_runtime_context


DEFAULT_BASE_URL = "http://127.0.0.1:80"
DEFAULT_FIXTURE_PATH = Path(__file__).resolve().parent / "fixtures" / "benchmark-doc.txt"


class ProvisioningError(Exception):
    """Raised when a provisioning step fails; the run must not proceed."""


def _http_request(
    url: str,
    method: str = "GET",
    body: bytes | None = None,
    headers: dict[str, str] | None = None,
    timeout: int = 30,
) -> tuple[int, dict[str, Any]]:
    """Make an HTTP request and return (status, parsed JSON result)."""
    req = urllib.request.Request(url, method=method, data=body, headers=headers or {}, unverifiable=True)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ssl._create_unverified_context()) as resp:
            text = resp.read().decode("utf-8", errors="replace")
            return resp.status, json.loads(text) if text else {}
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(text)
        except json.JSONDecodeError:
            return e.code, {"raw": text}


def _json_body(payload: dict) -> tuple[bytes, dict[str, str]]:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    return data, {"Content-Type": "application/json"}


def _auth_header(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _api_key_header(full_key: str) -> dict[str, str]:
    return {"X-API-Key": full_key}


def _extract_result(payload: dict) -> Any:
    """Extract the data payload from a Result<T> wrapper, if present."""
    if isinstance(payload, dict) and "data" in payload:
        return payload["data"]
    return payload


def _extract_code(payload: dict) -> int | None:
    if isinstance(payload, dict) and "code" in payload:
        return payload["code"]
    return None


def register_benchmark_user(base_url: str, run_set_id: str) -> tuple[str, str, str]:
    """Step 1: create a fresh benchmark user. Returns (username, email, password)."""
    username = f"bench-{run_set_id}-user"
    email = f"{username}@bench.intellidesk.local"
    password = str(uuid4())
    body, headers = _json_body({
        "username": username,
        "email": email,
        "password": password,
        "nickname": username,
    })
    status, payload = _http_request(f"{base_url}/api/auth/register", method="POST", body=body, headers=headers)
    code = _extract_code(payload)
    if status != 200 or (code is not None and code not in (0, 200)):
        raise ProvisioningError(f"register benchmark user failed: status={status}, payload={payload}")
    return username, email, password


def login_benchmark_user(base_url: str, username: str, password: str) -> str:
    """Step 2: login and return access token."""
    body, headers = _json_body({"username": username, "password": password})
    status, payload = _http_request(f"{base_url}/api/auth/login", method="POST", body=body, headers=headers)
    if status != 200:
        raise ProvisioningError(f"login failed: status={status}, payload={payload}")
    data = _extract_result(payload)
    access_token = data.get("accessToken") if isinstance(data, dict) else None
    if not access_token:
        raise ProvisioningError(f"login response missing accessToken: {payload}")
    return access_token


def create_workspace(base_url: str, access_token: str, run_set_id: str) -> str:
    """Step 4: create a fresh workspace. Returns workspace DB id."""
    body, headers = _json_body({
        "name": f"bench-{run_set_id}-workspace",
        "description": "Disposable benchmark workspace",
    })
    headers.update(_auth_header(access_token))
    status, payload = _http_request(f"{base_url}/api/workspaces", method="POST", body=body, headers=headers)
    if status != 200:
        raise ProvisioningError(f"create workspace failed: status={status}, payload={payload}")
    data = _extract_result(payload)
    workspace_id = data.get("id") if isinstance(data, dict) else None
    if not workspace_id:
        raise ProvisioningError(f"workspace response missing id: {payload}")
    return str(workspace_id)


def create_knowledge_base(base_url: str, access_token: str, workspace_id: str, run_set_id: str) -> str:
    """Step 5: create a fresh knowledge base. Returns KB DB id."""
    body, headers = _json_body({
        "name": f"bench-{run_set_id}-kb",
        "description": "Disposable benchmark KB",
        "chunkStrategy": "recursive",
        "chunkSize": 512,
        "chunkOverlap": 64,
    })
    headers.update(_auth_header(access_token))
    status, payload = _http_request(
        f"{base_url}/api/workspaces/{workspace_id}/knowledge-bases",
        method="POST",
        body=body,
        headers=headers,
    )
    if status != 200:
        raise ProvisioningError(f"create KB failed: status={status}, payload={payload}")
    data = _extract_result(payload)
    kb_id = data.get("id") if isinstance(data, dict) else None
    if not kb_id:
        raise ProvisioningError(f"KB response missing id: {payload}")
    return str(kb_id)


def _multipart_body(file_path: Path, field_name: str = "file") -> tuple[bytes, str]:
    boundary = f"----Boundary{uuid4().hex}"
    file_bytes = file_path.read_bytes()
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{field_name}"; filename="{file_path.name}"\r\n'
        f"Content-Type: text/plain\r\n\r\n".encode("utf-8")
        + file_bytes
        + f"\r\n--{boundary}--\r\n".encode("utf-8")
    )
    return body, f"multipart/form-data; boundary={boundary}"


def upload_fixture(
    base_url: str,
    access_token: str,
    workspace_id: str,
    knowledge_base_id: str,
    fixture_path: Path,
    timeout_seconds: int = 120,
    poll_interval_seconds: int = 2,
) -> str:
    """Step 6: upload deterministic fixture and wait for COMPLETED status."""
    body, content_type = _multipart_body(fixture_path)
    headers = {"Content-Type": content_type}
    headers.update(_auth_header(access_token))

    status, payload = _http_request(
        f"{base_url}/api/workspaces/{workspace_id}/knowledge-bases/{knowledge_base_id}/documents",
        method="POST",
        body=body,
        headers=headers,
        timeout=60,
    )
    if status not in (200, 202):
        raise ProvisioningError(f"upload fixture failed: status={status}, payload={payload}")
    data = _extract_result(payload)
    document_id = data.get("documentId") if isinstance(data, dict) else None
    if not document_id:
        raise ProvisioningError(f"upload response missing documentId: {payload}")

    # Poll until COMPLETED or FAILED.
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        headers = _auth_header(access_token)
        status, payload = _http_request(
            f"{base_url}/api/workspaces/{workspace_id}/knowledge-bases/{knowledge_base_id}/documents/{document_id}",
            method="GET",
            headers=headers,
        )
        if status != 200:
            raise ProvisioningError(f"poll document status failed: status={status}, payload={payload}")
        doc = _extract_result(payload)
        doc_status = doc.get("status") if isinstance(doc, dict) else None
        if doc_status == "COMPLETED":
            return str(document_id)
        if doc_status == "FAILED":
            raise ProvisioningError(f"document processing failed: {doc}")
        time.sleep(poll_interval_seconds)

    raise ProvisioningError(f"document processing timed out after {timeout_seconds}s")


def create_conversation(
    base_url: str,
    access_token: str,
    workspace_id: str,
    run_set_id: str,
    conversation_index: int | None = None,
) -> str:
    """Step 7: create a fresh conversation. Returns conversation DB id."""
    suffix = f"-{conversation_index}" if conversation_index is not None else ""
    body, headers = _json_body({"title": f"bench-{run_set_id}-conversation{suffix}"})
    headers.update(_auth_header(access_token))
    status, payload = _http_request(
        f"{base_url}/api/workspaces/{workspace_id}/conversations",
        method="POST",
        body=body,
        headers=headers,
    )
    if status != 200:
        raise ProvisioningError(f"create conversation failed: status={status}, payload={payload}")
    data = _extract_result(payload)
    conversation_id = data.get("id") if isinstance(data, dict) else None
    if not conversation_id:
        raise ProvisioningError(f"conversation response missing id: {payload}")
    return str(conversation_id)


def create_api_key(base_url: str, access_token: str, workspace_id: str, run_set_id: str) -> tuple[str, str]:
    """Step 8: create a fresh API key. Returns (api_key_id, fullKey)."""
    body, headers = _json_body({
        "name": f"bench-{run_set_id}-apikey",
        "scope": "READ",
    })
    headers.update(_auth_header(access_token))
    status, payload = _http_request(
        f"{base_url}/api/workspaces/{workspace_id}/api-keys",
        method="POST",
        body=body,
        headers=headers,
    )
    if status != 200:
        raise ProvisioningError(f"create API key failed: status={status}, payload={payload}")
    data = _extract_result(payload)
    if not isinstance(data, dict):
        raise ProvisioningError(f"API key response malformed: {payload}")
    api_key_id = data.get("id")
    full_key = data.get("fullKey")
    if not api_key_id or not full_key:
        raise ProvisioningError(f"API key response missing id/fullKey: {payload}")
    return str(api_key_id), full_key


def bearer_auth_smoke(base_url: str, access_token: str, workspace_id: str) -> None:
    """Step 9: verify Bearer access to a protected endpoint."""
    headers = _auth_header(access_token)
    status, payload = _http_request(
        f"{base_url}/api/workspaces/{workspace_id}/conversations",
        method="GET",
        headers=headers,
    )
    if status != 200:
        raise ProvisioningError(f"Bearer auth smoke failed: status={status}, payload={payload}")


def api_key_auth_smoke(base_url: str, full_key: str, workspace_id: str) -> None:
    """Step 10: verify API-Key access to a protected endpoint."""
    headers = _api_key_header(full_key)
    status, payload = _http_request(
        f"{base_url}/api/workspaces/{workspace_id}/knowledge-bases",
        method="GET",
        headers=headers,
    )
    if status != 200:
        raise ProvisioningError(f"API-Key auth smoke failed: status={status}, payload={payload}")


def ensure_fixture_file(path: Path | None = None) -> Path:
    """Return a deterministic fixture file, creating a minimal one if absent."""
    fixture = path or DEFAULT_FIXTURE_PATH
    if fixture.exists():
        return fixture
    fixture.parent.mkdir(parents=True, exist_ok=True)
    fixture.write_text(
        "This is a deterministic benchmark fixture document for IntelliDesk RAG pipeline testing.\n",
        encoding="utf-8",
    )
    return fixture


def _write_runtime_credentials(
    scenario: str,
    run_set_id: str,
    access_token: str,
    api_key_secret: str | None,
    bench_root: Path | str = "docs/evaluation/bench",
) -> Path:
    """Persist transient credentials for cleanup only.

    This file is NOT part of the formal evidence set; it exists only to let the
    cleanup step revoke/delete resources without requiring the runner process to
    keep the secrets in memory. It is deleted by cleanup after use.
    """
    credentials = {
        "schema_version": "1.0",
        "scenario": scenario,
        "run_set_id": run_set_id,
        "access_token": access_token,
        "api_key_secret": api_key_secret,
    }
    path = Path(bench_root) / scenario / run_set_id / "runtime_credentials.json"
    path.write_text(json.dumps(credentials, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def provision_run_set(
    scenario: str,
    run_set_id: str,
    base_url: str = DEFAULT_BASE_URL,
    fixture_path: Path | None = None,
    bench_root: Path | str = "docs/evaluation/bench",
    provisioning_logical_id: str | None = None,
    inject_env: bool = True,
) -> dict:
    """Execute the full v1.2 PRE_BENCH_PROVISIONING flow for a run-set.

    Returns the runtime_context dict written to disk.
    Raises ProvisioningError on any failure.
    """
    if scenario not in ("rag-pipeline", "api-key-auth", "http-concurrency"):
        raise ProvisioningError(f"unsupported scenario: {scenario}")

    logical_id = provisioning_logical_id or f"{scenario}-{run_set_id}"

    # Steps 1-2: fresh user + login.
    username, email, password = register_benchmark_user(base_url, run_set_id)
    access_token = login_benchmark_user(base_url, username, password)

    # Step 3: inject access token into process env (caller may also propagate).
    if inject_env:
        os.environ["BENCH_ACCESS_TOKEN"] = access_token

    # Steps 4-5: workspace + KB.
    workspace_id = create_workspace(base_url, access_token, run_set_id)
    knowledge_base_id = create_knowledge_base(base_url, access_token, workspace_id, run_set_id)

    # Step 6: upload fixture and wait.
    fixture = ensure_fixture_file(fixture_path)
    document_id = upload_fixture(base_url, access_token, workspace_id, knowledge_base_id, fixture)

    # Step 7: RAG uses one conversation per maximum formal k6 VU so concurrent
    # requests do not violate the application's one-generation-per-conversation
    # invariant. Other scenarios retain a single conversation for cleanup parity.
    conversation_count = 10 if scenario == "rag-pipeline" else 1
    conversation_ids = [
        create_conversation(
            base_url,
            access_token,
            workspace_id,
            run_set_id,
            index + 1 if conversation_count > 1 else None,
        )
        for index in range(conversation_count)
    ]
    conversation_id = conversation_ids[0]

    # Step 8: API key.
    api_key_id, full_key = create_api_key(base_url, access_token, workspace_id, run_set_id)
    if inject_env:
        os.environ["BENCH_API_KEY_SECRET"] = full_key

    # Steps 9-10: auth smokes.
    bearer_auth_smoke(base_url, access_token, workspace_id)
    api_key_auth_smoke(base_url, full_key, workspace_id)

    # Step 11: write runtime_context.
    context = build_runtime_context(
        scenario=scenario,
        run_set_id=run_set_id,
        provisioning_logical_id=logical_id,
        benchmark_user_logical_id=f"bench-{run_set_id}-user",
        workspace_id=workspace_id,
        conversation_id=conversation_id,
        conversation_ids=conversation_ids,
        knowledge_base_id=knowledge_base_id,
        document_ids=[document_id],
        api_key_id=api_key_id,
        fixture_identity="fixed-rag-prompt-v1.json",
    )
    write_runtime_context(scenario, run_set_id, context, bench_root=bench_root, fail_if_exists=True)
    _write_runtime_credentials(
        scenario=scenario,
        run_set_id=run_set_id,
        access_token=access_token,
        api_key_secret=full_key,
        bench_root=bench_root,
    )
    return context


def main(argv: list[str]) -> int:
    import argparse
    parser = argparse.ArgumentParser(description="Benchmark credential/resource provisioning")
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--run-set-id", required=True)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--fixture", type=Path, default=None)
    parser.add_argument("--bench-root", default="docs/evaluation/bench")
    parser.add_argument("--no-inject-env", action="store_true", help="Do not mutate process env")
    args = parser.parse_args(argv)

    try:
        context = provision_run_set(
            scenario=args.scenario,
            run_set_id=args.run_set_id,
            base_url=args.base_url,
            fixture_path=args.fixture,
            bench_root=args.bench_root,
            inject_env=not args.no_inject_env,
        )
        print(json.dumps({"status": "PASS", "runtime_context": context}, ensure_ascii=False, indent=2))
        return 0
    except ProvisioningError as e:
        print(json.dumps({"status": "FAIL", "reason": str(e)}, ensure_ascii=False, indent=2))
        return 1


if __name__ == "__main__":
    import sys
    sys.exit(main(sys.argv[1:]))
