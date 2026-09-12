"""Shared fail-closed Retrieval fixture/provider-path evidence contract."""

from __future__ import annotations

import re
from typing import Any


FIXTURE_VERSION = "retrieval-benchmark-fixture-v1"
FIXTURE_HASH = "6528792f29b3c910d1a126fcae3f50b6a04b0d0c7b7d06a307dbcf9b23aa69a2"
FIXTURE_MANIFEST = "docs/evaluation/bench/fixtures/retrieval-fixture-v1.json"
RERANKER_QUALITY_CONFIG_HASH = (
    "9cd3832be43b467c9591d9c4d94ebceb3cb851ca85210e98383aca94895c7f11"
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

STANDARD_CONTRACT_CLASS = "STANDARD_RETRIEVAL_CONTRACT"
RERANK_CONTRACT_CLASS = "REAL_RERANK_PROVIDER_CONTRACT"
STANDARD_PROVIDER_PATH_CLASS = "STANDARD_RETRIEVAL_PATH"
RERANK_PROVIDER_PATH_CLASS = "REAL_RERANK_PROVIDER_PATH"

MEASUREMENT_CONTRACTS = {
    STANDARD_CONTRACT_CLASS: {
        "warmup_min_operations": 200,
        "warmup_min_operation_duration_ms": 5000,
        "warmup_termination": "AND",
        "measured_min_operations": 1000,
        "measured_min_operation_duration_ms": 5000,
        "measured_max_wall_duration_ms": 180000,
        "request_timeout_ms": 30000,
        "minimum_duration_authority": "measured_wall_elapsed",
        "insufficient_sample_failure": "BENCH_INSUFFICIENT_SAMPLE_VOLUME",
    },
    RERANK_CONTRACT_CLASS: {
        "warmup_min_operations": 30,
        "warmup_min_operation_duration_ms": 120000,
        "warmup_termination": "AND",
        "measured_min_operations": 100,
        "measured_min_operation_duration_ms": 600000,
        "measured_max_wall_duration_ms": 900000,
        "request_timeout_ms": 60000,
        "minimum_duration_authority": "actual_operation_window_union",
        "insufficient_sample_failure": "BENCH_INSUFFICIENT_RERANK_SAMPLE_VOLUME",
    },
}

MODE_PROVIDER_PATH_CONTRACTS = {
    "VECTOR": {
        "provider_path_class": STANDARD_PROVIDER_PATH_CLASS,
        "contract_class": STANDARD_CONTRACT_CLASS,
    },
    "BM25": {
        "provider_path_class": STANDARD_PROVIDER_PATH_CLASS,
        "contract_class": STANDARD_CONTRACT_CLASS,
    },
    "HYBRID": {
        "provider_path_class": STANDARD_PROVIDER_PATH_CLASS,
        "contract_class": STANDARD_CONTRACT_CLASS,
    },
    "RERANK": {
        "provider_path_class": RERANK_PROVIDER_PATH_CLASS,
        "contract_class": RERANK_CONTRACT_CLASS,
    },
}


PERFORMANCE_CONFIG_EXTENSION = {
    "repeatability_p50_cv_max": 0.10,
    "repeatability_p95_max_deviation": 0.15,
    "rerank_p99_interpretation": "descriptive_only",
    "contract_classifier_version": "1.0",
    "provider_class_methodology_version": "1.0",
    "request_timeout_authority": "measurement_contracts",
    "request_timeout_methodology_version": "1.0",
    "measurement_contracts": MEASUREMENT_CONTRACTS,
    "mode_provider_path_contracts": MODE_PROVIDER_PATH_CONTRACTS,
    "provider_path_contract_version": "1.0",
    "fixture_version": FIXTURE_VERSION,
    "fixture_hash": FIXTURE_HASH,
    "fixture_manifest": FIXTURE_MANIFEST,
    "fixture_expected_documents": 14,
    "fixture_expected_chunks": 42,
    "expected_non_empty_for_every_query": True,
    "reranker_quality_config_hash": RERANKER_QUALITY_CONFIG_HASH,
    "measured_provider_path_fail_closed": True,
}


def contract_for_mode(mode: str) -> tuple[str, str, dict]:
    """Return the statically frozen contract for an exact mode identity."""
    if not isinstance(mode, str) or not re.fullmatch(
        r"(?:VECTOR|BM25|HYBRID|RERANK)-c(?:1|4|8)", mode
    ):
        raise ValueError("retrieval contract classification failed")
    functional_mode = mode.split("-c", 1)[0]
    mapping = MODE_PROVIDER_PATH_CONTRACTS.get(functional_mode)
    if not isinstance(mapping, dict):
        raise ValueError("retrieval contract classification failed")
    contract_class = mapping.get("contract_class")
    provider_path_class = mapping.get("provider_path_class")
    contract = MEASUREMENT_CONTRACTS.get(str(contract_class))
    if not isinstance(contract, dict) or not isinstance(provider_path_class, str):
        raise ValueError("retrieval contract classification failed")
    return str(contract_class), provider_path_class, contract


def validate_run_observation(mode: str, observation: Any) -> dict:
    """Validate run-owned contract and warmup evidence without duplicate measured truth."""
    if not isinstance(observation, dict):
        raise ValueError("retrieval run observation missing")
    contract_class, provider_path_class, contract = contract_for_mode(mode)
    if observation.get("contract_class") != contract_class \
            or observation.get("provider_path_class") != provider_path_class:
        raise ValueError("retrieval run contract classification mismatch")
    operations = observation.get("warmup_completed_operations")
    duration = observation.get("warmup_operation_duration_ms")
    if type(operations) is not int or operations < contract["warmup_min_operations"]:
        raise ValueError("retrieval warmup completed operations below contract")
    if type(duration) is not int or duration < contract["warmup_min_operation_duration_ms"]:
        raise ValueError("retrieval warmup operation duration below contract")
    if observation.get("measurement_started_after_warmup") is not True:
        raise ValueError("retrieval measured window did not start after warmup")
    return contract


def _measured_durations(records: list[dict]) -> tuple[float, float]:
    intervals: list[tuple[float, float]] = []
    for record in records:
        start = record.get("run_relative_time")
        latency = record.get("latency_ms")
        if isinstance(start, bool) or not isinstance(start, (int, float)) \
                or isinstance(latency, bool) or not isinstance(latency, (int, float)) \
                or float(start) < 0 or float(latency) < 0:
            raise ValueError("retrieval measured timing evidence invalid")
        intervals.append((float(start), float(start) + float(latency)))
    if not intervals:
        raise ValueError("retrieval measured raw is empty")
    intervals.sort()
    union_ms = 0.0
    current_start, current_end = intervals[0]
    for start, end in intervals[1:]:
        if start <= current_end:
            current_end = max(current_end, end)
        else:
            union_ms += current_end - current_start
            current_start, current_end = start, end
    union_ms += current_end - current_start
    wall_ms = max(end for _, end in intervals)
    return union_ms, wall_ms


def validate_measured_run(mode: str, records: list[dict]) -> dict:
    """Fail closed on sample/duration evidence before any percentile is created."""
    contract_class, provider_path_class, contract = contract_for_mode(mode)
    if len(records) < contract["measured_min_operations"]:
        raise ValueError(f"{mode}: measured operations below contract")
    actual_union_ms, wall_ms = _measured_durations(records)
    if wall_ms > contract["measured_max_wall_duration_ms"]:
        raise ValueError(f"{mode}: measured wall duration exceeds contract ceiling")
    authority_ms = (
        actual_union_ms
        if contract["minimum_duration_authority"] == "actual_operation_window_union"
        else wall_ms
    )
    if authority_ms < contract["measured_min_operation_duration_ms"]:
        raise ValueError(f"{mode}: measured operation duration below contract")
    return {
        "contract_class": contract_class,
        "provider_path_class": provider_path_class,
        "measured_operations": len(records),
        "measured_operation_duration_ms": actual_union_ms,
        "measured_wall_duration_ms": wall_ms,
    }


def validate_preflight(manifest: dict, preflight: dict) -> None:
    """Reject any Formal candidate without linked, successful real-path preflight."""
    expected = {
        "schema_version": "1.1",
        "scenario": "retrieval",
        "run_set_id": manifest.get("run_set_id"),
        "classification": "NOT_BENCHMARK_EVIDENCE",
        "formal_percentile_use_prohibited": True,
        "success": True,
        "fixture_version": FIXTURE_VERSION,
        "fixture_hash": FIXTURE_HASH,
        "document_count": 14,
        "chunk_count": 42,
        "query_relationships_checked": 69,
        "rerank_provider_call_count": 69,
        "reranker_quality_config_hash": RERANKER_QUALITY_CONFIG_HASH,
    }
    for field, value in expected.items():
        if preflight.get(field) != value:
            raise ValueError(f"retrieval preflight {field} mismatch")
    if not isinstance(preflight.get("observed_at"), str):
        raise ValueError("retrieval preflight timestamp missing")

    facts = preflight.get("fixture_facts")
    if not isinstance(facts, dict):
        raise ValueError("retrieval preflight fixture facts missing")
    expected_facts = {
        "databaseReachable": True,
        "workspaceExists": True,
        "knowledgeBaseExists": True,
        "completedDocumentCount": 14,
        "chunkCount": 42,
        "embeddedChunkCount": 42,
        "minEmbeddingDimension": 1536,
        "maxEmbeddingDimension": 1536,
        "readyTaskCount": 14,
        "generationMatchedChunkCount": 42,
        "fixtureHashMatches": True,
        "scopeMatches": True,
        "queryRelationshipsComplete": True,
        "elasticsearchReachable": True,
        "elasticsearchIndexExists": True,
        "elasticsearchDocumentCount": 42,
        "elasticsearchIdentityMatchesPostgres": True,
        "elasticsearchContentHashesMatch": True,
    }
    for field, value in expected_facts.items():
        if facts.get(field) != value:
            raise ValueError(f"retrieval preflight fixture fact {field} mismatch")

    aggregates = preflight.get("mode_path_aggregates")
    if not isinstance(aggregates, dict) or set(aggregates) != {"VECTOR", "BM25", "HYBRID", "RERANK"}:
        raise ValueError("retrieval preflight mode-path matrix mismatch")
    for mode, aggregate in aggregates.items():
        if not isinstance(aggregate, dict) or aggregate.get("queries_checked") != 69:
            raise ValueError(f"retrieval preflight {mode} query count mismatch")
        for field in (
            "minimum_candidate_count_before_hydration",
            "minimum_hydrated_result_count",
            "minimum_final_result_count",
        ):
            value = aggregate.get(field)
            if type(value) is not int or value <= 0:
                raise ValueError(f"retrieval preflight {mode} {field} is not positive")

    provider = preflight.get("rerank_provider_identity")
    if not isinstance(provider, dict):
        raise ValueError("retrieval live reranker identity missing")
    if provider.get("model_id") != "BAAI/bge-reranker-v2-m3" \
            or provider.get("reranker_quality_config_hash") != RERANKER_QUALITY_CONFIG_HASH \
            or provider.get("live_identity_fields_match") is not True \
            or not SHA256_RE.fullmatch(str(provider.get("provider_info_sha256", ""))):
        raise ValueError("retrieval live reranker identity mismatch")


def validate_sample_metadata(mode: str, metadata: Any) -> None:
    """Validate observed provider-path facts for one authoritative measured sample."""
    if not isinstance(metadata, dict):
        raise ValueError("execution_metadata missing")
    _, _, contract = contract_for_mode(mode)
    if metadata.get("request_timeout_ms") != contract["request_timeout_ms"]:
        raise ValueError("provider-class request timeout mismatch")
    functional_mode = mode.split("-c", 1)[0]
    for field in ("candidate_count_before_hydration", "hydrated_result_count", "result_count"):
        value = metadata.get(field)
        if type(value) is not int or value <= 0:
            raise ValueError(f"provider-path {field} is not positive")

    vector = metadata.get("vector_candidate_count")
    bm25 = metadata.get("bm25_candidate_count")
    hybrid = metadata.get("hybrid_candidate_count")
    rerank_candidates = metadata.get("rerank_candidate_count")
    rerank_executed = metadata.get("rerank_executed")
    provider_calls = metadata.get("rerank_provider_call_count")
    post_rerank = metadata.get("post_rerank_hydrated_result_count")
    if functional_mode == "VECTOR":
        if type(vector) is not int or vector <= 0:
            raise ValueError("provider-path VECTOR candidate count is not positive")
    elif functional_mode == "BM25":
        if type(bm25) is not int or bm25 <= 0:
            raise ValueError("provider-path BM25 candidate count is not positive")
    elif functional_mode == "HYBRID":
        if any(type(value) is not int or value <= 0 for value in (vector, bm25, hybrid)):
            raise ValueError("provider-path HYBRID inputs/fusion are not positive")
    elif functional_mode == "RERANK":
        if any(type(value) is not int or value <= 0
               for value in (vector, bm25, hybrid, rerank_candidates, post_rerank)):
            raise ValueError("provider-path RERANK stages are not positive")
        if rerank_executed is not True or provider_calls != 1:
            raise ValueError("provider-path RERANK provider call was not observed exactly once")
    else:
        raise ValueError(f"unknown retrieval mode: {functional_mode}")
