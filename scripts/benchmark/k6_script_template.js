import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

/**
 * k6 script template for Phase 8 Wave 2 benchmark harness.
 *
 * This template is parameterized via environment variables:
 *   - BENCH_TARGET: target endpoint URL (required; no fallback)
 *   - BENCH_TARGETS: optional JSON array of per-VU isolated targets
 *   - BENCH_BASE_URL: base URL used only when BENCH_TARGET is a relative path
 *   - BENCH_SCENARIO: scenario name (propagated to tags)
 *   - BENCH_RUN_SET_ID: run-set id
 *   - BENCH_RUN_ID: run id encoding level + independent-run-index (e.g. "vu1-run-1")
 *   - BENCH_VU_LEVEL: formal VU level, must be one of 1/5/10 (default 1)
 *   - BENCH_RAMP_DURATION: ramp-up duration (default "5s")
 *   - BENCH_SUSTAIN_DURATION: sustained-load duration (default "30s")
 *   - BENCH_WARMUP_POLICY: warmup handling policy, default "exclude" from measurement
 *   - BENCH_HTTP_METHOD: GET | POST
 *   - BENCH_REQUEST_BODY: request body string (for POST)
 *   - BENCH_CONTENT_TYPE: content-type header
 *   - BENCH_EXTRA_HEADERS: JSON object string of extra headers
 *   - BENCH_AUTH_MODE: none | session_cookie | api_key_header | bearer_header
 *   - BENCH_AUTH_SOURCE: env-var name or file path containing the credential
 *   - BENCH_PROVIDER_MODE: provider mode tag
 *   - BENCH_TTFT_MODE: true | false (use TTFT helper for SSE)
 *   - BENCH_TTFT_HELPER_URL: URL of the TTFT helper server (default http://127.0.0.1:18181/measure)
 *   - BENCH_EXPECTED_STATUS: expected HTTP status
 *   - BENCH_TIMEOUT_MS: request timeout (default 30000)
 *   - BENCH_POST_TTFT_SETTLE_DELAY_MS: unmeasured delay after TTFT (default 0)
 *
 * Measurement window: only the sustain stage is authoritative. Each sample is
 * tagged with "phase" ("ramp", "sustain", "cooldown"). Downstream collectors
 * should filter raw records to phase == "sustain" when computing percentiles.
 * The default BENCH_WARMUP_POLICY is "exclude"; implementations may record the
 * policy but the authoritative measurement window is the 30s sustain stage.
 *
 * Authoritative per-sample metric: bench_req_duration (custom Trend). For the
 * HTTP Concurrency scenario its value is copied without quantization from
 * Response.timings.duration (sending + waiting + receiving). The collector
 * converts these Point samples into raw records. Other native HTTP timing
 * fields remain non-canonical telemetry.
 *
 * The helper path is evaluation-only: k6 cannot incrementally parse SSE, so for
 * SSE TTFT scenarios the real request is made by scripts/benchmark/ttft_transport.py,
 * which still traverses localhost:80 -> Nginx -> backend and returns real t0/t1.
 */

if (!__ENV.BENCH_TARGET) {
    throw new Error('BENCH_TARGET environment variable is required');
}
const target = __ENV.BENCH_TARGET;
const baseUrl = __ENV.BENCH_BASE_URL || 'http://127.0.0.1:80';
let targetPool = [];
if (__ENV.BENCH_TARGETS) {
    try {
        targetPool = JSON.parse(__ENV.BENCH_TARGETS);
    } catch (e) {
        throw new Error('BENCH_TARGETS must be a JSON array');
    }
    if (!Array.isArray(targetPool) || targetPool.length === 0) {
        throw new Error('BENCH_TARGETS must be a non-empty JSON array');
    }
}
const scenario = __ENV.BENCH_SCENARIO || 'unknown-scenario';
const runSetId = __ENV.BENCH_RUN_SET_ID || 'unknown-run-set';
const runId = __ENV.BENCH_RUN_ID || 'run-1';
const vuLevel = parseInt(__ENV.BENCH_VU_LEVEL || '1');
const rampDuration = __ENV.BENCH_RAMP_DURATION || '5s';
const sustainDuration = __ENV.BENCH_SUSTAIN_DURATION || '30s';
const warmupPolicy = __ENV.BENCH_WARMUP_POLICY || 'exclude';
const httpMethod = (__ENV.BENCH_HTTP_METHOD || 'GET').toUpperCase();
const requestBodyRaw = __ENV.BENCH_REQUEST_BODY || '';
const contentType = __ENV.BENCH_CONTENT_TYPE || 'application/json';
const extraHeadersRaw = __ENV.BENCH_EXTRA_HEADERS || '{}';
const authMode = __ENV.BENCH_AUTH_MODE || 'none';
const authSource = __ENV.BENCH_AUTH_SOURCE || '';
const providerMode = __ENV.BENCH_PROVIDER_MODE || 'unknown';
const ttftMode = (__ENV.BENCH_TTFT_MODE || 'false').toLowerCase() === 'true';
const ttftHelperUrl = __ENV.BENCH_TTFT_HELPER_URL || 'http://127.0.0.1:18181/measure';
const expectedStatus = parseInt(__ENV.BENCH_EXPECTED_STATUS || '200');
const timeoutMs = __ENV.BENCH_TIMEOUT_MS || '30000';
const postTtftSettleDelayMs = parseInt(__ENV.BENCH_POST_TTFT_SETTLE_DELAY_MS || '0');

function absoluteTarget(candidate) {
    return candidate.startsWith('http://') || candidate.startsWith('https://')
        ? candidate
        : baseUrl + candidate;
}

function resolveEffectiveTarget() {
    const candidate = targetPool.length > 0
        ? targetPool[(__VU - 1) % targetPool.length]
        : target;
    return absoluteTarget(candidate);
}

// Approximate test start for phase tagging; good enough to distinguish ramp,
// sustain, and cooldown windows.
const testStartMs = Date.now();
const rampMs = parseDuration(rampDuration);
const sustainMs = parseDuration(sustainDuration);

function parseDuration(value) {
    const match = String(value).match(/^(\d+(?:\.\d+)?)\s*(s|m|h)$/i);
    if (!match) {
        throw new Error('invalid duration format: ' + value);
    }
    const scalar = parseFloat(match[1]);
    const unit = match[2].toLowerCase();
    if (unit === 's') return scalar * 1000;
    if (unit === 'm') return scalar * 60 * 1000;
    if (unit === 'h') return scalar * 60 * 60 * 1000;
    return scalar * 1000;
}

function resolvePhase() {
    const elapsed = Date.now() - testStartMs;
    if (elapsed < rampMs) {
        return 'ramp';
    }
    if (elapsed < rampMs + sustainMs) {
        return 'sustain';
    }
    return 'cooldown';
}

export const options = {
    scenarios: {
        bench: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: rampDuration, target: vuLevel },
                { duration: sustainDuration, target: vuLevel },
            ],
            gracefulRampDown: '0s',
        },
    },
    tags: {
        scenario: scenario,
        run_set_id: runSetId,
        run_id: runId,
    },
};

const benchDuration = new Trend('bench_req_duration', true);
const HTTP_CONCURRENCY_LATENCY_SOURCE = 'k6_response_timings_duration';
const HTTP_CONCURRENCY_LATENCY_BOUNDARY =
    'sending_plus_waiting_plus_receiving_excludes_blocked_dns_connect_tls';
const HTTP_CONCURRENCY_LATENCY_QUANTIZATION = 'none';

function resolveCredential(source) {
    if (!source) {
        return null;
    }
    const fromEnv = __ENV[source];
    if (fromEnv) {
        return fromEnv;
    }
    return null;
}

function buildBaseHeaders() {
    const headers = {};
    try {
        const extra = JSON.parse(extraHeadersRaw);
        Object.assign(headers, extra);
    } catch (e) {
        // Ignore malformed extra headers.
    }
    if (httpMethod === 'POST' && contentType) {
        headers['Content-Type'] = contentType;
    }
    return headers;
}

function injectAuth(headers) {
    if (authMode === 'none' || !authSource) {
        return;
    }
    const credential = resolveCredential(authSource);
    if (!credential) {
        return;
    }
    if (authMode === 'api_key_header') {
        headers['X-API-Key'] = credential;
    } else if (authMode === 'bearer_header') {
        headers['Authorization'] = 'Bearer ' + credential;
    } else if (authMode === 'session_cookie') {
        headers['Cookie'] = credential;
    }
}

function makeTags(statusCode, success, error, extraTags, effectiveTarget) {
    const tags = {
        scenario: scenario,
        run_set_id: runSetId,
        run_id: runId,
        url: effectiveTarget,
        metric_name: 'bench_req_duration',
        provider_mode: providerMode,
        vu: String(vuLevel),
        concurrency: String(vuLevel),
        status: String(statusCode),
        success: String(success),
        phase: resolvePhase(),
        warmup_policy: warmupPolicy,
    };
    if (error) {
        tags.error = error;
    }
    if (extraTags) {
        Object.assign(tags, extraTags);
    }
    return tags;
}

function requestBody() {
    if (!requestBodyRaw) {
        return null;
    }
    return requestBodyRaw;
}

function nativeHttpConcurrencyLatencyMs(response) {
    const latencyMs = response && response.timings
        ? Number(response.timings.duration)
        : NaN;
    if (!Number.isFinite(latencyMs) || latencyMs < 0) {
        throw new Error('k6 Response.timings.duration must be a finite nonnegative value');
    }
    return latencyMs;
}

function executeDirect(effectiveTarget) {
    const headers = buildBaseHeaders();
    injectAuth(headers);
    const body = requestBody();
    const params = {
        headers: headers,
        tags: {
            scenario: scenario,
            run_set_id: runSetId,
            run_id: runId,
        },
        timeout: timeoutMs + 'ms',
    };

    let res;
    // HTTP Concurrency uses the native fractional-ms response duration. Other
    // direct scenarios retain their previously frozen timing implementation;
    // this amendment is deliberately scenario-scoped.
    const legacyStartMs = scenario === 'http-concurrency' ? null : Date.now();
    if (httpMethod === 'POST') {
        res = http.post(effectiveTarget, body, params);
    } else {
        res = http.get(effectiveTarget, params);
    }
    const latencyMs = scenario === 'http-concurrency'
        ? nativeHttpConcurrencyLatencyMs(res)
        : Date.now() - legacyStartMs;

    const statusCode = res.status;
    const success = statusCode === expectedStatus;
    const error = success ? null : ('status=' + statusCode);

    check(res, {
        'status matches expected': () => success,
    });

    return {
        statusCode,
        success,
        error,
        latencyMs,
        latencyMeasurementSource: scenario === 'http-concurrency'
            ? HTTP_CONCURRENCY_LATENCY_SOURCE
            : 'legacy_date_now_delta',
        latencyRequestBoundary: scenario === 'http-concurrency'
            ? HTTP_CONCURRENCY_LATENCY_BOUNDARY
            : 'legacy_direct_request_call',
        latencyPrePercentileQuantization: scenario === 'http-concurrency'
            ? HTTP_CONCURRENCY_LATENCY_QUANTIZATION
            : 'integer_milliseconds',
    };
}

function executeTtftHelper(effectiveTarget) {
    // v1.2: k6 must send only auth_mode + credential_source identity to the
    // helper. The helper resolves the actual secret from its own process env.
    // Do NOT inject raw credentials into headers/body sent to the helper.
    const headers = buildBaseHeaders();
    const body = requestBody();

    const spec = {
        url: effectiveTarget,
        method: httpMethod,
        headers: headers,
        body: body,
        content_type: contentType,
        auth_mode: authMode,
        auth_source: authSource,
        expected_status: expectedStatus,
        timeout_ms: parseInt(timeoutMs),
    };

    const helperParams = {
        headers: { 'Content-Type': 'application/json' },
        tags: {
            scenario: scenario,
            run_set_id: runSetId,
            run_id: runId,
        },
        timeout: timeoutMs + 'ms',
    };

    const res = http.post(ttftHelperUrl, JSON.stringify(spec), helperParams);
    const statusCode = res.status;
    let helperResult = {};
    try {
        helperResult = JSON.parse(res.body || '{}');
    } catch (e) {
        helperResult = { error: 'failed to parse helper response: ' + String(res.body) };
    }

    const success = statusCode === 200 && helperResult.success === true;
    const error = helperResult.error || (success ? null : 'helper status=' + statusCode);

    check(res, {
        'helper returned success': () => success,
    });

    return {
        statusCode: helperResult.status_code || statusCode,
        success: success,
        error: error,
        // In TTFT mode the authoritative benchmark metric is the interval to
        // the first qualifying token, not the time until the SSE stream closes.
        latencyMs: helperResult.ttft_ms,
        helperResult: helperResult,
    };
}

export default function () {
    const effectiveTarget = resolveEffectiveTarget();
    let result;
    if (ttftMode) {
        result = executeTtftHelper(effectiveTarget);
    } else {
        result = executeDirect(effectiveTarget);
    }

    const extraTags = {};
    if (result.helperResult) {
        const hr = result.helperResult;
        if (hr.t0_monotonic !== undefined && hr.t0_monotonic !== null) {
            extraTags.t0_monotonic = String(hr.t0_monotonic);
        }
        if (hr.t1_monotonic !== undefined && hr.t1_monotonic !== null) {
            extraTags.t1_monotonic = String(hr.t1_monotonic);
        }
        if (hr.ttft_ms !== undefined && hr.ttft_ms !== null) {
            extraTags.ttft_ms = String(hr.ttft_ms);
        }
        if (hr.qualifying_event_type) {
            extraTags.qualifying_event_type = hr.qualifying_event_type;
        }
    }
    if (result.latencyMeasurementSource) {
        extraTags.latency_measurement_source = result.latencyMeasurementSource;
    }
    if (result.latencyRequestBoundary) {
        extraTags.latency_request_boundary = result.latencyRequestBoundary;
    }
    if (result.latencyPrePercentileQuantization) {
        extraTags.latency_pre_percentile_quantization = result.latencyPrePercentileQuantization;
    }

    const tags = makeTags(result.statusCode, result.success, result.error, extraTags, effectiveTarget);
    const latencyMs = result.latencyMs === undefined || result.latencyMs === null
        ? 0
        : result.latencyMs;
    benchDuration.add(latencyMs, tags);
    if (ttftMode && postTtftSettleDelayMs > 0) {
        sleep(postTtftSettleDelayMs / 1000.0);
    }
}
