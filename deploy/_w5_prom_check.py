import json, urllib.request, urllib.parse

BASE = "http://localhost:9090/api/v1/query"
queries = {
    "1 http_req_rate": 'sum(rate(http_server_requests_seconds_count{application="intellidesk"}[5m])) by (uri, method)',
    "2 http_err_rate": '(sum(rate(http_server_requests_seconds_count{application="intellidesk",status=~"4..|5.."}[5m])) or vector(0)) / clamp_min(sum(rate(http_server_requests_seconds_count{application="intellidesk"}[5m])), 1e-9) * 100',
    "3 http_p95": 'histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="intellidesk"}[5m])) by (le))',
    "4 jvm_heap": 'sum(jvm_memory_used_bytes{application="intellidesk",area="heap"}) by (id)',
    "5 jvm_threads": 'jvm_threads_live_threads{application="intellidesk"}',
    "6 gc_rate": 'sum(rate(jvm_gc_pause_seconds_count{application="intellidesk"}[5m])) by (cause)',
    "7 hikari_active": 'hikaricp_connections_active{application="intellidesk"}',
    "8 proc_cpu": 'process_cpu_usage{application="intellidesk"}',
    "9 sys_cpu": 'system_cpu_usage{application="intellidesk"}',
    "10 up": 'up{job="intellidesk-backend"}',
}
ok = True
# Histogram bucket existence probe (panel 3 relies on it)
_b = json.load(urllib.request.urlopen(BASE + "?" + urllib.parse.urlencode({
    "query": 'http_server_requests_seconds_bucket{application="intellidesk"}',
}), timeout=8))
print(f"[bucket] http_server_requests_seconds_bucket series={len(_b['data']['result'])}")
for name, q in queries.items():
    url = BASE + "?" + urllib.parse.urlencode({"query": q})
    try:
        d = json.load(urllib.request.urlopen(url, timeout=8))
    except Exception as e:
        ok = False
        print(f"[{name}] HTTP-EXC {e}")
        continue
    if d.get("status") != "success":
        ok = False
        print(f"[{name}] ERROR {d.get('error','')}")
    else:
        n = len(d["data"]["result"])
        print(f"[{name}] OK series={n}")
        if n == 0:
            ok = False
print("ALL_POPQL_OK" if ok else "PROMQL_ISSUES")