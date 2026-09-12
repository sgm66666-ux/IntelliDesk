/**
 * IntelliDesk Phase 3 E2E — Deterministic HTTP Provider Fixtures (Node.js)
 * Serves OpenAI-compatible embedding and DashScope-compatible rerank endpoints.
 */
const http = require('http');
const crypto = require('crypto');

const EMBEDDING_PORT = 18080;
const RERANK_PORT = 18081;
const EMBEDDING_DIM = 1536;

// --- Deterministic embedding generator ---
function deterministicEmbedding(text) {
    const hash = crypto.createHash('sha256').update(text, 'utf-8').digest();
    const vec = [];
    for (let i = 0; i < EMBEDDING_DIM; i++) {
        const offset = (i * 4) % hash.length;
        const val = hash.readFloatBE(offset);
        vec.push(Math.round(Math.max(-1.0, Math.min(1.0, val)) * 1000000) / 1000000);
    }
    return vec;
}

// --- Deterministic rerank scorer ---
function deterministicRerankScore(query, document) {
    const queryTokens = new Set(query.toLowerCase().split(/\s+/));
    const docTokens = new Set(document.toLowerCase().split(/\s+/));
    if (queryTokens.size === 0) return 0.0;
    let overlap = 0;
    for (const t of queryTokens) {
        if (docTokens.has(t)) overlap++;
    }
    const base = 0.3 + 0.65 * (overlap / queryTokens.size);
    const hash = crypto.createHash('md5').update(query + document).digest();
    const jitter = hash[0] / 2560.0;
    return Math.round((base + jitter) * 1000000) / 1000000;
}

// --- Helper to send JSON response ---
function sendJson(res, code, data) {
    const body = JSON.stringify(data);
    res.writeHead(code, {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
        'Connection': 'close'
    });
    res.end(body);
}

function readBody(req) {
    return new Promise((resolve) => {
        let body = '';
        req.on('data', chunk => body += chunk);
        req.on('end', () => resolve(body));
    });
}

// --- Embedding Server (OpenAI-compatible) ---
const embeddingServer = http.createServer(async (req, res) => {
    if (req.method === 'GET') {
        sendJson(res, 200, { status: 'ok', service: 'embedding-stub' });
        return;
    }

    if (req.method !== 'POST' || req.url !== '/v1/embeddings') {
        sendJson(res, 404, { error: { message: 'Not found', type: 'not_found' } });
        return;
    }

    const auth = req.headers['authorization'] || '';
    if (!auth.startsWith('Bearer ')) {
        sendJson(res, 401, { error: { message: 'Missing API key', type: 'auth_error' } });
        return;
    }

    const apiKey = auth.slice(7);
    if (apiKey === 'test-failure-key') {
        sendJson(res, 500, { error: { message: 'Internal server error', type: 'server_error' } });
        return;
    }
    if (apiKey === 'test-timeout-key') {
        await new Promise(r => setTimeout(r, 30000));
        sendJson(res, 504, { error: { message: 'Gateway timeout' } });
        return;
    }
    if (apiKey === 'test-429-key') {
        sendJson(res, 429, { error: { message: 'Rate limit exceeded', type: 'rate_limit' } });
        return;
    }

    try {
        const body = JSON.parse(await readBody(req));
        const model = body.model || '';
        let inputs = body.input || [];
        if (typeof inputs === 'string') inputs = [inputs];

        const data = inputs.map((text, idx) => ({
            object: 'embedding',
            index: idx,
            embedding: deterministicEmbedding(text)
        }));

        sendJson(res, 200, {
            object: 'list',
            data,
            model,
            usage: {
                prompt_tokens: inputs.reduce((s, t) => s + t.split(/\s+/).length, 0),
                total_tokens: inputs.reduce((s, t) => s + t.split(/\s+/).length, 0)
            }
        });
    } catch (e) {
        sendJson(res, 400, { error: { message: 'Invalid request: ' + e.message, type: 'invalid_request' } });
    }
});

// --- Rerank Server (DashScope-compatible) ---
const rerankServer = http.createServer(async (req, res) => {
    if (req.method === 'GET') {
        sendJson(res, 200, { status: 'ok', service: 'rerank-stub' });
        return;
    }

    if (req.method !== 'POST') {
        sendJson(res, 404, { error: { message: 'Not found', type: 'not_found' } });
        return;
    }

    const validPaths = [
        '/api/v1/services/rerank/text-rerank/text-rerank',
        '/compatible-mode/v1/rerank'
    ];
    if (!validPaths.includes(req.url)) {
        sendJson(res, 404, { error: { message: 'Not found', type: 'not_found' } });
        return;
    }

    const auth = req.headers['authorization'] || '';
    if (!auth.startsWith('Bearer ')) {
        sendJson(res, 401, { error: { message: 'Missing API key', type: 'auth_error' } });
        return;
    }

    const apiKey = auth.slice(7);
    if (apiKey === 'test-rerank-failure-key') {
        sendJson(res, 500, { error: { message: 'Internal server error', type: 'server_error' } });
        return;
    }
    if (apiKey === 'test-rerank-timeout-key') {
        await new Promise(r => setTimeout(r, 30000));
        sendJson(res, 504, { error: { message: 'Gateway timeout' } });
        return;
    }
    if (apiKey === 'test-rerank-429-key') {
        sendJson(res, 429, { error: { message: 'Rate limit exceeded', type: 'rate_limit' } });
        return;
    }

    try {
        const body = JSON.parse(await readBody(req));
        const model = body.model || '';
        const input = body.input || {};
        const query = input.query || '';
        const documents = input.documents || [];

        const results = documents.map((doc, idx) => ({
            index: idx,
            relevance_score: deterministicRerankScore(query, doc)
        }));

        results.sort((a, b) => b.relevance_score - a.relevance_score);

        sendJson(res, 200, {
            results,
            model,
            usage: {
                total_tokens: documents.reduce((s, d) => s + d.split(/\s+/).length, 0) + query.split(/\s+/).length
            }
        });
    } catch (e) {
        sendJson(res, 400, { error: { message: 'Invalid request: ' + e.message, type: 'invalid_request' } });
    }
});

// --- Start servers ---
embeddingServer.listen(EMBEDDING_PORT, '127.0.0.1', () => {
    console.log(`[embedding] listening on http://127.0.0.1:${EMBEDDING_PORT}`);
});

rerankServer.listen(RERANK_PORT, '127.0.0.1', () => {
    console.log(`[rerank]    listening on http://127.0.0.1:${RERANK_PORT}`);
});

console.log('='.repeat(60));
console.log('IntelliDesk Phase 3 E2E — Deterministic HTTP Provider Fixtures');
console.log('='.repeat(60));
console.log(`Embedding: http://127.0.0.1:${EMBEDDING_PORT}/v1/embeddings`);
console.log(`Rerank:    http://127.0.0.1:${RERANK_PORT}/api/v1/services/rerank/text-rerank/text-rerank`);
console.log('='.repeat(60));

// Keep process alive
process.on('SIGINT', () => {
    console.log('\nShutting down...');
    embeddingServer.close();
    rerankServer.close();
    process.exit(0);
});