import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent.parent
run_set = root / "docs" / "evaluation" / "raw" / "real-quality" / "2ae529f6aa1ba508" / "implementation-compensation-001"

a = json.loads((run_set / "metrics_summary.json").read_text(encoding="utf-8"))
b = json.loads((run_set / "metrics_summary_independent_recompute.json").read_text(encoding="utf-8"))


def normalize(x):
    if isinstance(x, dict):
        return {k: normalize(v) for k, v in sorted(x.items()) if k not in ("derived_from",)}
    if isinstance(x, list):
        return [normalize(v) for v in x]
    return x


na = normalize(a)
nb = normalize(b)

if na == nb:
    print("MATCH: metrics_summary.json == metrics_summary_independent_recompute.json (ignoring derived_from)")
else:
    print("MISMATCH")
    print(json.dumps(na, indent=2)[:2000])
    print("---")
    print(json.dumps(nb, indent=2)[:2000])
    raise SystemExit(1)
