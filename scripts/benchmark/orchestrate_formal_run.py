#!/usr/bin/env python3
"""End-to-end orchestration for one Phase 8 Wave 2 formal benchmark run-set.

Executes, in order:
  1. PRE_BENCH_PROVISIONING (once per run-set)
  2. k6 benchmark run matrix: VU levels 1/5/10 × 3 independent runs per level
  3. Offline percentile computation per raw file
  4. POST_BENCH_CLEANUP
  5. Run-set manifest finalization

This is a necessary glue script because the existing atomic tools do not
propagate transient credentials across process boundaries.
"""

import argparse
import hashlib
import json
import os
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from cleanup import cleanup_run_set
from freeze_benchmark_config import config_id
from environment_identity import get_backend_rate_limit_enabled
from k6_collector import collect_k6_output
from k6_runner import (
    build_k6_env,
    extract_hashes,
    load_config,
    preflight_k6_binary,
    resolve_k6_path,
    run_k6,
)
from percentile import compute_percentiles, load_latencies
from provisioning import provision_run_set


# Formal benchmark matrix: VU levels and independent runs per level.
VU_LEVELS = [1, 5, 10]
INDEPENDENT_RUNS = [1, 2, 3]
EXPECTED_RUNS_PER_MODE = len(VU_LEVELS) * len(INDEPENDENT_RUNS)


def load_credentials(run_set_dir: Path) -> dict:
    cred_path = run_set_dir / "runtime_credentials.json"
    if not cred_path.exists():
        raise FileNotFoundError(f"runtime_credentials.json missing: {cred_path}")
    return json.loads(cred_path.read_text(encoding="utf-8"))


def remove_transient_credentials(run_set_dir: Path) -> None:
    """Remove the secret-bearing handoff file regardless of cleanup outcome."""
    cred_path = run_set_dir / "runtime_credentials.json"
    if cred_path.exists():
        cred_path.unlink()


def atomic_create_json(path: Path, value: dict) -> None:
    """Create a final artifact exactly once without exposing partial bytes."""
    if path.exists():
        raise FileExistsError(f"FAIL_IF_EXISTS: {path}")
    fd, temporary_name = tempfile.mkstemp(prefix=path.name + ".tmp-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary_name, path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def prepare_fresh_run_set(
    run_set_dir: Path,
    frozen_config_source: Path,
    scenario: str,
) -> tuple[dict, str, dict]:
    """Preflight k6 first, then atomically establish a fresh Formal run-set."""
    if not frozen_config_source.is_file():
        raise FileNotFoundError(f"frozen config source missing: {frozen_config_source}")
    frozen_config = load_config(frozen_config_source)
    if frozen_config.get("scenario") != scenario:
        raise ValueError(
            f"frozen config scenario mismatch: expected={scenario}, actual={frozen_config.get('scenario')}"
        )

    # Resolution and --version happen before even checking/creating the target
    # directory so a bad launcher environment cannot consume a Formal ID.
    resolved_k6, resolution_source = resolve_k6_path()
    preflight = preflight_k6_binary(
        resolved_k6,
        expected_version=frozen_config.get("k6_version"),
    )
    preflight["resolution_source"] = resolution_source

    if run_set_dir.exists():
        raise FileExistsError(f"FAIL_IF_EXISTS: {run_set_dir}")
    run_set_dir.mkdir(parents=True, exist_ok=False)
    destination = run_set_dir / "frozen_config.json"
    try:
        with frozen_config_source.open("rb") as source, destination.open("xb") as target:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                target.write(chunk)
        atomic_create_json(run_set_dir / "launcher_preflight.json", preflight)
    except Exception:
        # The directory was just created by this function and no benchmark or
        # provisioning has started. Remove only files created in this attempt.
        for path in (run_set_dir / "launcher_preflight.json", destination):
            if path.exists():
                path.unlink()
        run_set_dir.rmdir()
        raise
    return frozen_config, resolved_k6, preflight


def run_k6_for_mode(
    scenario: str,
    mode: str,
    run_set_id: str,
    run_id: str,
    vu_level: int,
    independent_run_index: int,
    run_set_dir: Path,
    frozen_config: dict,
    env_overrides: dict,
    preflighted_k6_path: str,
) -> str:
    """Run one k6 measurement and return the produced raw file name."""
    config_hash, environment_hash = extract_hashes(frozen_config)
    perf = frozen_config.get("performance_config", {})
    fixture_dir = Path(__file__).resolve().parent / "fixtures"
    bench_root = Path("docs/evaluation/bench")

    args = argparse.Namespace(
        scenario=scenario,
        run_set_id=run_set_id,
        run_id=run_id,
        target=None,
        duration=perf.get("sustain_duration", perf.get("duration", "30s")),
        vus=vu_level,
        vu_level=vu_level,
        independent_run_index=independent_run_index,
        mode=mode,
        k6_path=preflighted_k6_path,
        k6_preflighted=True,
        bench_root=str(bench_root),
        config_path=str(run_set_dir / "frozen_config.json"),
        no_helper=False,
        auth_mode=env_overrides.get("auth_mode"),
        auth_source=env_overrides.get("auth_source"),
    )

    env = build_k6_env(args, perf, fixture_dir, bench_root)
    env.update(env_overrides)

    result = run_k6(args, env)
    if result["returncode"] != 0:
        raise RuntimeError(f"k6 run failed: {result['stderr']}")

    raw_file_name = f"raw-{scenario}-{mode}-{run_id}.jsonl"
    collect_k6_output(
        k6_output_path=result["k6_output_path"],
        run_set_dir=result["run_set_dir"],
        scenario=scenario,
        run_set_id=run_set_id,
        run_id=run_id,
        mode=mode,
        config_hash=config_hash,
        environment_hash=environment_hash,
        run_start_time=env.get("BENCH_RUN_START_TIME"),
    )
    return raw_file_name


def compute_and_write_percentiles(run_set_dir: Path, raw_name: str) -> dict:
    raw_path = run_set_dir / raw_name
    if not raw_path.exists():
        raise FileNotFoundError(f"raw file missing: {raw_path}")
    latencies = load_latencies(raw_path)
    percentiles = compute_percentiles(latencies)
    out_name = raw_name.replace("raw-", "percentiles-").replace(".jsonl", ".json")
    output_path = run_set_dir / out_name
    result = {
        "input": str(raw_path),
        "sample_count": len(latencies),
        "percentiles": percentiles,
        "method": "nearest-rank",
    }
    output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result


def finalize_manifest(
    run_set_dir: Path,
    scenario: str,
    run_set_id: str,
    config_hash: str,
    expected_modes: list[str],
    runs: list[dict],
    measurement_status: str,
    cleanup_status: str,
    launcher_preflight: dict | None = None,
) -> None:
    manifest_path = run_set_dir / "run_set_manifest.json"
    manifest = {
        "schema_version": "1.0",
        "evidence_class": "bench",
        "scenario": scenario,
        "config_hash": config_hash,
        "config_id": config_id(config_hash),
        "run_set_id": run_set_id,
        "expected_modes": expected_modes,
        "expected_runs_per_mode": EXPECTED_RUNS_PER_MODE,
        "status": "COMPLETE" if measurement_status == "COMPLETE" and cleanup_status == "COMPLETE" else "COMPLETE_WITH_CLEANUP_FAILURE" if measurement_status == "COMPLETE" else measurement_status,
        "measurement_status": measurement_status,
        "cleanup_status": cleanup_status,
        "measurement_valid": measurement_status == "COMPLETE",
        # Execution/cleanup completion is necessary but cannot self-certify
        # final acceptance. run_set_acceptance.py must independently validate
        # raw provenance, sustain evidence, and derived percentiles.
        "final_acceptance_eligible": False,
        "final_acceptance_status": (
            "PENDING_EVIDENCE_VALIDATION"
            if measurement_status == "COMPLETE" and cleanup_status == "COMPLETE"
            else "NOT_ELIGIBLE"
        ),
        "runs": runs,
        "finalized_at": datetime.now(timezone.utc).isoformat(),
    }
    if launcher_preflight is not None:
        preflight_path = run_set_dir / "launcher_preflight.json"
        manifest.update({
            "launcher_preflight_file": preflight_path.name,
            "launcher_preflight_sha256": _file_sha256(preflight_path),
            "k6_resolved_path": launcher_preflight["resolved_path"],
            "k6_version": launcher_preflight["observed_version"],
            "k6_binary_sha256": launcher_preflight["binary_sha256"],
        })
    atomic_create_json(manifest_path, manifest)


def orchestrate(
    scenario: str,
    run_set_id: str,
    run_id: str | None = None,
    vus: int | None = None,
    frozen_config_source: Path | None = None,
) -> int:
    run_set_dir = Path("docs/evaluation/bench") / scenario / run_set_id
    if frozen_config_source is not None:
        frozen_config, resolved_k6, launcher_preflight = prepare_fresh_run_set(
            run_set_dir, frozen_config_source, scenario
        )
    else:
        if not run_set_dir.exists():
            raise FileNotFoundError(f"run-set directory does not exist: {run_set_dir}")
        if (run_set_dir / "run_set_manifest.json").exists():
            raise FileExistsError(f"FAIL_IF_EXISTS: finalized/failed run-set already has a manifest: {run_set_dir}")
        frozen_config_path = run_set_dir / "frozen_config.json"
        if not frozen_config_path.exists():
            raise FileNotFoundError(f"frozen_config.json missing: {frozen_config_path}")
        frozen_config = load_config(frozen_config_path)
        resolved_k6, resolution_source = resolve_k6_path()
        launcher_preflight = preflight_k6_binary(
            resolved_k6, expected_version=frozen_config.get("k6_version")
        )
        launcher_preflight["resolution_source"] = resolution_source
        if not (run_set_dir / "launcher_preflight.json").exists():
            atomic_create_json(run_set_dir / "launcher_preflight.json", launcher_preflight)
    config_hash, _ = extract_hashes(frozen_config)
    perf = frozen_config.get("performance_config", {})
    if scenario in {"rag-pipeline", "api-key-auth"}:
        if perf.get("application_rate_limit_policy") != "disabled_for_controlled_performance_measurement":
            raise ValueError("authenticated external benchmark must freeze the controlled rate-limit policy")
        effective_rate_limit = get_backend_rate_limit_enabled()
        if effective_rate_limit != "false":
            raise RuntimeError(
                "authenticated external benchmark requires RATE_LIMIT_ENABLED=false; "
                f"effective value is {effective_rate_limit!r}"
            )

    # Determine run modes.
    if scenario == "api-key-auth":
        modes = [
            ("bearer-baseline", {"auth_mode": "bearer_header", "auth_source": "BENCH_ACCESS_TOKEN"}),
            ("api-key", {"auth_mode": "api_key_header", "auth_source": "BENCH_API_KEY_SECRET"}),
        ]
        expected_modes = ["bearer-baseline", "api-key"]
    else:
        modes = [(scenario, {})]
        expected_modes = [scenario]

    # Optional single-shot CLI override for smoke testing.
    single_run_mode = run_id is not None or vus is not None
    levels = [vus] if single_run_mode and vus is not None else VU_LEVELS
    indices = [1] if single_run_mode else INDEPENDENT_RUNS
    if single_run_mode:
        modes = [(run_id or modes[0][0], modes[0][1])]

    measurement_status = "COMPLETE"
    run_records = []

    try:
        # 1. Provision once per run-set.
        provision_run_set(
            scenario=scenario,
            run_set_id=run_set_id,
            inject_env=True,
        )
        credentials = load_credentials(run_set_dir)

        # 2. Run the formal matrix for each mode before cleanup.
        for mode_name, overrides in modes:
            for level in levels:
                for index in indices:
                    run_id = f"vu{level}-run-{index}"
                    env_overrides = dict(overrides)
                    raw_file_name = run_k6_for_mode(
                        scenario=scenario,
                        mode=mode_name,
                        run_set_id=run_set_id,
                        run_id=run_id,
                        vu_level=level,
                        independent_run_index=index,
                        run_set_dir=run_set_dir,
                        frozen_config=frozen_config,
                        env_overrides=env_overrides,
                        preflighted_k6_path=resolved_k6,
                    )
                    percentiles = compute_and_write_percentiles(run_set_dir, raw_file_name)
                    run_records.append({
                        "run_id": run_id,
                        "mode": mode_name,
                        "vu_level": level,
                        "independent_run_index": index,
                        "raw_file": raw_file_name,
                        "percentiles": percentiles,
                    })

    except Exception as e:
        measurement_status = "FAILED"
        run_records.append({"error": str(e)})
        print(json.dumps({"status": "FAILED", "phase": "measurement", "reason": str(e)}, ensure_ascii=False, indent=2), file=sys.stderr)

    # 3. Cleanup once after all k6 runs.
    cleanup_status = "COMPLETE"
    try:
        credentials = load_credentials(run_set_dir)
        cleanup_run_set(
            scenario=scenario,
            run_set_id=run_set_id,
            access_token=credentials["access_token"],
        )
    except Exception as e:
        cleanup_status = "FAILED"
        print(json.dumps({"status": "FAILED", "phase": "cleanup", "reason": str(e)}, ensure_ascii=False, indent=2), file=sys.stderr)
    finally:
        # A failed cleanup is retained as evidence, but credentials never are.
        remove_transient_credentials(run_set_dir)

    # 4. Finalize manifest.
    finalize_manifest(
        run_set_dir=run_set_dir,
        scenario=scenario,
        run_set_id=run_set_id,
        config_hash=config_hash,
        expected_modes=expected_modes,
        runs=run_records,
        measurement_status=measurement_status,
        cleanup_status=cleanup_status,
        launcher_preflight=launcher_preflight,
    )

    final_status = "COMPLETE" if measurement_status == "COMPLETE" and cleanup_status == "COMPLETE" else "FAILED"
    print(json.dumps({"status": final_status, "run_set_dir": str(run_set_dir)}, ensure_ascii=False, indent=2))
    return 0 if final_status == "COMPLETE" else 1


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Orchestrate one formal benchmark run-set")
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--run-set-id", required=True)
    parser.add_argument("--run-id", default=None, help="Override run id for single-shot smoke (default: formal matrix)")
    parser.add_argument("--vus", type=int, default=None, help="Override VU level for single-shot smoke")
    parser.add_argument(
        "--frozen-config-source",
        type=Path,
        default=None,
        help="source freeze for a fresh run-set; k6 is preflighted before the directory is created",
    )
    args = parser.parse_args(argv)
    return orchestrate(
        args.scenario,
        args.run_set_id,
        run_id=args.run_id,
        vus=args.vus,
        frozen_config_source=args.frozen_config_source,
    )


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
