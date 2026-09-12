#!/usr/bin/env python3
"""Runtime context artifact management for Phase 8 Wave 2 benchmark harness.

Canonical path (frozen by v1.2 amendment):

    docs/evaluation/bench/<scenario>/<run-set-id>/runtime_context.json

Ownership key: (scenario, run_set_id).
Each runtime context is an immutable, independent snapshot.
"""

import json
import os
import re
import tempfile
from datetime import datetime, timezone
from pathlib import Path


RUNTIME_CONTEXT_SCHEMA_VERSION = "1.0"
BENCH_ROOT = Path("docs/evaluation/bench")
PLACEHOLDER_PATTERN = re.compile(r"\{([a-zA-Z_][a-zA-Z0-9_]*)\}")

# Logical placeholders that may appear in request body fixtures.
REQUEST_BODY_PLACEHOLDERS = {"knowledgeBaseIds"}


def runtime_context_path(scenario: str, run_set_id: str, bench_root: Path | str = BENCH_ROOT) -> Path:
    """Return the canonical runtime_context.json path for a scenario/run-set."""
    return Path(bench_root) / scenario / run_set_id / "runtime_context.json"


def _validate_schema(context: dict) -> None:
    """Validate that the context dict satisfies the frozen runtime_context schema."""
    required = {
        "schema_version",
        "scenario",
        "run_set_id",
        "provisioning_logical_id",
        "benchmark_user_logical_id",
        "workspace_id",
        "conversation_id",
        "knowledge_base_id",
        "document_ids",
        "api_key_id",
        "fixture_identity",
        "provisioned_at",
    }
    missing = required - set(context.keys())
    if missing:
        raise ValueError(f"runtime_context missing required fields: {sorted(missing)}")

    # Runtime IDs must be present and not placeholders.
    for key in ("workspace_id", "conversation_id", "knowledge_base_id", "api_key_id"):
        value = context.get(key)
        if value is None or value == "":
            raise ValueError(f"runtime_context missing runtime id for {key}")

    conversation_ids = context.get("conversation_ids")
    if conversation_ids is not None:
        if not isinstance(conversation_ids, list) or not conversation_ids:
            raise ValueError("runtime_context conversation_ids must be a non-empty list")
        normalized_ids = [str(value) for value in conversation_ids]
        if any(not value for value in normalized_ids):
            raise ValueError("runtime_context conversation_ids must not contain blank ids")
        if len(set(normalized_ids)) != len(normalized_ids):
            raise ValueError("runtime_context conversation_ids must contain unique ids")
        if str(context["conversation_id"]) not in normalized_ids:
            raise ValueError("runtime_context primary conversation_id must be in conversation_ids")

    # Secrets must never be persisted.
    for forbidden in ("access_token", "api_key_secret", "full_key", "refresh_token"):
        if forbidden in context:
            raise ValueError(f"runtime_context must not contain secret field: {forbidden}")

    canonical = json.dumps(context, sort_keys=True, ensure_ascii=False).lower()
    for secret_marker in ("bearer ", "isk_", "sk-", "ak-"):
        if secret_marker in canonical:
            raise ValueError(f"runtime_context appears to contain a raw credential: {secret_marker}")


def write_runtime_context(
    scenario: str,
    run_set_id: str,
    context: dict,
    bench_root: Path | str = BENCH_ROOT,
    fail_if_exists: bool = True,
) -> Path:
    """Atomically write a runtime_context.json file.

    Args:
        scenario: scenario name.
        run_set_id: run-set id.
        context: runtime context dict (will be validated against schema).
        bench_root: benchmark namespace root.
        fail_if_exists: if True (default), raise FileExistsError when the file
            already exists. This enforces immutability per (scenario, run_set_id).

    Returns:
        Path to the written runtime_context.json.
    """
    if context.get("scenario") != scenario:
        raise ValueError(f"context scenario mismatch: {context.get('scenario')} != {scenario}")
    if context.get("run_set_id") != run_set_id:
        raise ValueError(f"context run_set_id mismatch: {context.get('run_set_id')} != {run_set_id}")

    _validate_schema(context)

    target = runtime_context_path(scenario, run_set_id, bench_root)
    target.parent.mkdir(parents=True, exist_ok=True)

    if fail_if_exists and target.exists():
        raise FileExistsError(f"FAIL_IF_EXISTS: runtime_context already exists: {target}")

    # Atomic write: write to a temp file in the same directory, then rename.
    data = json.dumps(context, ensure_ascii=False, indent=2, sort_keys=True)
    fd, tmp_path = tempfile.mkstemp(dir=target.parent, prefix=".runtime_context_", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_path, target)
    except Exception:
        try:
            os.unlink(tmp_path)
        except FileNotFoundError:
            pass
        raise

    return target


def read_runtime_context(
    scenario: str,
    run_set_id: str,
    bench_root: Path | str = BENCH_ROOT,
) -> dict:
    """Read the runtime_context.json for the current scenario/run-set.

    Raises FileNotFoundError if missing (fail-closed).
    """
    path = runtime_context_path(scenario, run_set_id, bench_root)
    if not path.exists():
        raise FileNotFoundError(f"runtime_context missing for scenario={scenario}, run_set_id={run_set_id}: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def build_runtime_context(
    scenario: str,
    run_set_id: str,
    provisioning_logical_id: str,
    benchmark_user_logical_id: str,
    workspace_id: str | int,
    conversation_id: str | int,
    knowledge_base_id: str | int,
    document_ids: list[str | int],
    api_key_id: str | int,
    fixture_identity: str,
    conversation_ids: list[str | int] | None = None,
) -> dict:
    """Build a validated runtime_context dict (no secrets)."""
    context = {
        "schema_version": RUNTIME_CONTEXT_SCHEMA_VERSION,
        "scenario": scenario,
        "run_set_id": run_set_id,
        "provisioning_logical_id": provisioning_logical_id,
        "benchmark_user_logical_id": benchmark_user_logical_id,
        "workspace_id": str(workspace_id),
        "conversation_id": str(conversation_id),
        "knowledge_base_id": str(knowledge_base_id),
        "document_ids": [str(d) for d in document_ids],
        "api_key_id": str(api_key_id),
        "fixture_identity": fixture_identity,
        "provisioned_at": datetime.now(timezone.utc).isoformat(),
    }
    if conversation_ids is not None:
        context["conversation_ids"] = [str(value) for value in conversation_ids]
    _validate_schema(context)
    return context


def substitute_target(template: str, context: dict) -> str:
    """Substitute {workspace_id} and {conversation_id} placeholders in a target URL.

    Raises ValueError on unknown placeholders or missing values.
    """
    allowed = {"workspace_id", "conversation_id"}

    def replacer(match: re.Match) -> str:
        key = match.group(1)
        if key not in allowed:
            raise ValueError(f"unknown placeholder in target template: {{{key}}}")
        value = context.get(key)
        if value is None or value == "":
            raise ValueError(f"missing runtime value for placeholder: {{{key}}}")
        return str(value)

    result = PLACEHOLDER_PATTERN.sub(replacer, template)
    if PLACEHOLDER_PATTERN.search(result):
        raise ValueError(f"unresolved placeholder remains in target: {result}")
    return result


def substitute_request_body(body_text: str, context: dict) -> str:
    """Substitute logical placeholders inside a request body fixture.

    Currently supports replacing the "knowledgeBaseIds" list with the runtime
    knowledge_base_id from the context. Raises ValueError if the body still
    contains unresolved placeholders.
    """
    try:
        body = json.loads(body_text)
    except json.JSONDecodeError as e:
        raise ValueError(f"request body is not valid JSON: {e}") from e

    if not isinstance(body, dict):
        raise ValueError("request body must be a JSON object")

    # knowledgeBaseIds substitution.
    if "knowledgeBaseIds" in body:
        kb_id = context.get("knowledge_base_id")
        if kb_id is None or kb_id == "":
            raise ValueError("missing knowledge_base_id for knowledgeBaseIds substitution")
        body["knowledgeBaseIds"] = [int(kb_id)]

    result_text = json.dumps(body, ensure_ascii=False, separators=(",", ":"))

    # Defensive: detect any remaining {placeholder} strings in the body.
    if PLACEHOLDER_PATTERN.search(result_text):
        raise ValueError(f"unresolved placeholder remains in request body: {result_text}")
    return result_text


def load_and_substitute(
    scenario: str,
    run_set_id: str,
    target_template: str,
    request_body_identity: str | None,
    fixture_dir: Path,
    bench_root: Path | str = BENCH_ROOT,
) -> tuple[str, str]:
    """Load runtime context and substitute target + request body.

    Returns (effective_target, effective_body).
    """
    context = read_runtime_context(scenario, run_set_id, bench_root)

    effective_target = substitute_target(target_template, context)

    if request_body_identity:
        fixture_path = fixture_dir / f"{request_body_identity}.json"
        if not fixture_path.exists():
            raise FileNotFoundError(f"request body fixture not found: {fixture_path}")
        body_text = fixture_path.read_text(encoding="utf-8")
        effective_body = substitute_request_body(body_text, context)
    else:
        effective_body = ""

    return effective_target, effective_body


def require_runtime_context(scenario: str, run_set_id: str, bench_root: Path | str = BENCH_ROOT) -> dict:
    """Fail-closed helper: runtime context must exist and be valid."""
    context = read_runtime_context(scenario, run_set_id, bench_root)
    _validate_schema(context)
    return context
