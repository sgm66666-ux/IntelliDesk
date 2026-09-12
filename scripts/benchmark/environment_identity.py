#!/usr/bin/env python3
"""Collect benchmark execution environment identity for Phase 8 Wave 2.

Outputs a JSON object with non-secret environment attributes. Never reads or
writes API keys, access tokens, passwords, credentials, or private environment
variables.
"""

import json
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path


SECRET_PATTERNS = ("api_key", "apikey", "access_token", "authorization", "password",
                   "secret", "private_key", "credential")


def safe_env(name: str, default: str | None = None) -> str | None:
    """Read an environment variable if it is not a known secret key."""
    value = os.environ.get(name)
    if value is None:
        return default
    lowered = name.lower()
    if any(pattern in lowered for pattern in SECRET_PATTERNS):
        return None
    return value


def run_command(cmd: list[str], timeout: int = 10) -> str | None:
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        if result.returncode == 0:
            return result.stdout.strip().splitlines()[0]
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        pass
    return None


def _resolve_tool(name: str, home_env: str, bin_dir: str,
                  known_paths: list[str] | None = None) -> str | None:
    """Resolve a tool binary using env home, known fallback paths, and PATH."""
    home = os.environ.get(home_env)
    if home:
        candidate = Path(home) / bin_dir / name
        if platform.system() == "Windows":
            for suffix in (".exe", ".cmd", ".bat"):
                with_suffix = candidate.with_suffix(suffix)
                if with_suffix.exists():
                    return str(with_suffix)
        if candidate.exists():
            return str(candidate)

    if known_paths:
        for p in known_paths:
            candidate = Path(p)
            if candidate.exists():
                return str(candidate)

    from_path = shutil.which(name)
    if from_path:
        return from_path

    return None


def get_k6_version() -> str | None:
    k6_path = os.environ.get("K6_PATH")
    if k6_path and Path(k6_path).exists():
        return run_command([k6_path, "version"])
    for candidate in [r"C:\tools\k6\k6-v2.2.0-windows-amd64\k6.exe",
                      r"C:\tools\k6\k6.exe"]:
        if Path(candidate).exists():
            return run_command([candidate, "version"])
    from_path = shutil.which("k6")
    if from_path:
        return run_command([from_path, "version"])
    return None


def get_java_version() -> str | None:
    java_bin = _resolve_tool(
        "java", "JAVA_HOME", "bin",
        [r"C:\tools\jdk-21\jdk-21.0.9+10\bin\java.exe"]
    )
    if not java_bin:
        return None
    try:
        result = subprocess.run([java_bin, "-version"], capture_output=True, text=True, timeout=10)
        text = (result.stdout + result.stderr).strip()
        for line in text.splitlines():
            if "version" in line.lower():
                return line.strip()
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        pass
    return None


def get_maven_version() -> str | None:
    mvn_bin = _resolve_tool(
        "mvn", "M2_HOME", "bin",
        [r"C:\tools\apache-maven-3.9.16\bin\mvn.cmd"]
    )
    if not mvn_bin:
        mvn_bin = _resolve_tool(
            "mvn", "MAVEN_HOME", "bin",
            [r"C:\tools\apache-maven-3.9.16\bin\mvn.cmd"]
        )
    if not mvn_bin:
        return None
    env = os.environ.copy()
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        # Wave 2 environment constraint: JDK 21.0.9+10.
        java_home = r"C:\tools\jdk-21\jdk-21.0.9+10"
    env["JAVA_HOME"] = java_home
    try:
        result = subprocess.run([mvn_bin, "-version"], capture_output=True, text=True, timeout=30, env=env)
        if result.returncode == 0:
            return result.stdout.strip().splitlines()[0]
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        pass
    return None


def get_node_version() -> str | None:
    return run_command(["node", "--version"])


def get_npm_version() -> str | None:
    npm_bin = _resolve_tool("npm", "", "", [])
    if npm_bin:
        return run_command([npm_bin, "--version"])
    # On Windows npm is often a .cmd not visible to subprocess without shell.
    return run_command(["cmd", "/c", "npm", "--version"])


def get_docker_version() -> str | None:
    return run_command(["docker", "--version"])


def get_docker_compose_version() -> str | None:
    return run_command(["docker", "compose", "version"])


def _docker_exec(service: str, cmd: list[str], timeout: int = 10) -> str | None:
    try:
        result = subprocess.run(
            ["docker", "exec", service] + cmd,
            capture_output=True, text=True, timeout=timeout
        )
        if result.returncode == 0:
            return result.stdout.strip()
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        pass
    return None


def _docker_compose_ps(service: str) -> str | None:
    try:
        result = subprocess.run(
            ["docker", "compose", "ps", "--format", "json", service],
            capture_output=True, text=True, timeout=10
        )
        if result.returncode == 0:
            return result.stdout.strip()
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        pass
    return None


def get_elasticsearch_version() -> str | None:
    return _docker_exec("intellidesk-elasticsearch", ["curl", "-s", "http://localhost:9200/"])


def get_postgres_version() -> str | None:
    user = os.environ.get("POSTGRES_USER", "postgres")
    db = os.environ.get("POSTGRES_DB", "intellidesk")
    return _docker_exec("intellidesk-postgres", ["psql", "-U", user, "-d", db, "-c", "SELECT version();"])


def get_backend_rate_limit_enabled() -> str:
    """Return the effective non-secret backend rate-limit switch."""
    try:
        result = subprocess.run(
            ["docker", "inspect", "--format", "{{json .Config.Env}}", "intellidesk-backend"],
            capture_output=True, text=True, timeout=10,
        )
        if result.returncode == 0:
            for entry in json.loads(result.stdout.strip()):
                if entry.startswith("RATE_LIMIT_ENABLED="):
                    return entry.split("=", 1)[1].strip().lower()
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError, json.JSONDecodeError):
        pass
    return "true(default)"


def get_backend_provider_identity() -> dict:
    """Read only non-secret provider endpoint/model switches from the backend."""
    allowed = {
        "INTELLIDESK_CHAT_BASE_URL": "chat_base_url",
        "INTELLIDESK_CHAT_MODEL": "chat_model",
        "EMBEDDING_BASE_URL": "embedding_base_url",
        "EMBEDDING_MODEL": "embedding_model",
        "RERANK_ENABLED": "rerank_enabled",
        "RERANK_BASE_URL": "rerank_base_url",
        "RERANK_MODEL": "rerank_model",
    }
    identity = {value: "unknown" for value in allowed.values()}
    try:
        result = subprocess.run(
            ["docker", "inspect", "--format", "{{json .Config.Env}}", "intellidesk-backend"],
            capture_output=True, text=True, timeout=10,
        )
        if result.returncode == 0:
            for entry in json.loads(result.stdout.strip()):
                name, separator, value = entry.partition("=")
                if separator and name in allowed:
                    identity[allowed[name]] = value
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError, json.JSONDecodeError):
        pass
    return identity


def get_ollama_version() -> str | None:
    ok, status, detail = _ollama_http("/api/version")
    if ok and status == 200:
        try:
            payload = json.loads(detail)
            return payload.get("version", detail)
        except json.JSONDecodeError:
            return detail
    return None


def _ollama_http(path: str) -> tuple[bool, int | None, str]:
    try:
        import urllib.request
        req = urllib.request.Request(f"http://127.0.0.1:11434{path}", method="GET")
        with urllib.request.urlopen(req, timeout=5) as resp:
            return True, resp.status, resp.read().decode("utf-8", errors="replace")
    except Exception as e:
        return False, None, str(e)


def get_ollama_model_identity() -> str | None:
    ok, status, detail = _ollama_http("/api/tags")
    if not ok or status != 200:
        return None
    try:
        payload = json.loads(detail)
        for model in payload.get("models", []):
            name = model.get("name", "")
            if "qwen3-embedding:8b" in name:
                return f"{name} {model.get('digest', '')}".strip()
        return "not found"
    except json.JSONDecodeError:
        return None


def get_verified_embedding_dimension(model: str = "qwen3-embedding:8b",
                                      requested_dimensions: int = 1536) -> int | None:
    """Verify the production-compatible embedding dimension via /v1/embeddings.

    This is a runtime measurement using the same OpenAI-compatible path the
    project uses in production. It returns the actual dimension returned by the
    provider, or None if the provider is unreachable.
    """
    try:
        import urllib.request
        req = urllib.request.Request(
            "http://127.0.0.1:11434/v1/embeddings",
            method="POST",
            data=json.dumps({
                "model": model,
                "input": "environment identity dimension verification",
                "dimensions": requested_dimensions,
            }).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
            data = payload.get("data", [])
            embedding = None
            if isinstance(data, list) and len(data) > 0:
                embedding = data[0].get("embedding", [])
            elif "embedding" in payload:
                embedding = payload.get("embedding", [])
            if isinstance(embedding, list):
                return len(embedding)
    except Exception:
        pass
    return None


def get_reranker_gateway_identity() -> str | None:
    try:
        import urllib.request
        req = urllib.request.Request("http://127.0.0.1:18182/health", method="GET")
        with urllib.request.urlopen(req, timeout=2) as resp:
            if resp.status == 200:
                return resp.read().decode("utf-8").strip()
    except Exception:
        pass
    return None


def get_backend_application_identity() -> str | None:
    """External backend identity via a permitAll endpoint.

    /api/health requires authentication, so the preflight uses /api/auth/login
    which returns 400 for an invalid payload. Any HTTP response proves the
    backend + Nginx are reachable.
    """
    try:
        import urllib.request
        import urllib.error
        data = json.dumps({"email": "preflight@intellidesk.local", "password": "preflight"}).encode("utf-8")
        req = urllib.request.Request(
            "http://127.0.0.1:80/api/auth/login",
            method="POST",
            data=data,
            headers={"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=2) as resp:
                return f"status={resp.status}; body={resp.read().decode('utf-8', errors='replace').strip()[:200]}"
        except urllib.error.HTTPError as e:
            return f"status={e.code}; body={e.read().decode('utf-8', errors='replace').strip()[:200]}"
    except Exception:
        pass
    return None


def collect_environment_identity(extra: dict | None = None) -> dict:
    """Return a canonical environment identity map."""
    identity = {
        "os": platform.system(),
        "architecture": platform.machine(),
        "cpu_identity": platform.processor() or "unknown",
        "cpu_logical_count": os.cpu_count(),
        "memory": "unknown",
        "java_version": get_java_version() or "unknown",
        "maven_version": get_maven_version() or "unknown",
        "node_version": get_node_version() or "unknown",
        "npm_version": get_npm_version() or "unknown",
        "k6_version": get_k6_version() or "unknown",
        "docker_version": get_docker_version() or "unknown",
        "docker_compose_version": get_docker_compose_version() or "unknown",
        "backend_application_identity": get_backend_application_identity() or "unknown",
        "backend_rate_limit_enabled": get_backend_rate_limit_enabled(),
        "backend_provider_identity": get_backend_provider_identity(),
        "elasticsearch_identity": get_elasticsearch_version() or "unknown",
        "postgres_identity": get_postgres_version() or "unknown",
        "ollama_version": get_ollama_version() or "unknown",
        "embedding_model_identity": get_ollama_model_identity() or "unknown",
        "configured_dimension": 1536,
        "verified_returned_dimension": get_verified_embedding_dimension() or "unknown",
        "reranker_gateway_identity": get_reranker_gateway_identity() or "unknown",
        "reranker_model_identity": "BAAI/bge-reranker-v2-m3",
    }

    # Memory: try to read total physical memory without external dependencies.
    try:
        if platform.system() == "Windows":
            import ctypes
            kernel32 = ctypes.windll.kernel32
            mem_status = ctypes.c_ulonglong(0)
            kernel32.GetPhysicallyInstalledSystemMemory(ctypes.byref(mem_status))
            identity["memory"] = f"{mem_status.value // 1024} MB"
        elif platform.system() == "Linux":
            with open("/proc/meminfo", "r", encoding="utf-8") as f:
                for line in f:
                    if line.startswith("MemTotal:"):
                        identity["memory"] = line.split(":")[1].strip()
                        break
        elif platform.system() == "Darwin":
            out = run_command(["sysctl", "-n", "hw.memsize"])
            if out:
                identity["memory"] = f"{int(out) // (1024 ** 3)} GB"
    except Exception:
        pass

    if extra:
        for key, value in extra.items():
            lowered = key.lower()
            if any(pattern in lowered for pattern in SECRET_PATTERNS):
                raise ValueError(f"refusing to include secret-like key in environment identity: {key}")
        identity["extra"] = extra

    return identity


def main(argv: list[str]) -> int:
    import argparse
    parser = argparse.ArgumentParser(description="Collect benchmark environment identity")
    parser.add_argument("--output", "-o", help="Output JSON file")
    args = parser.parse_args(argv)

    identity = collect_environment_identity()
    result = json.dumps(identity, ensure_ascii=False, indent=2)
    print(result)

    if args.output:
        Path(args.output).write_text(result, encoding="utf-8")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
