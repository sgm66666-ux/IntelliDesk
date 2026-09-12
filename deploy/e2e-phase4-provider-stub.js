/**
 * IntelliDesk Phase 4 E2E — Deterministic Chat HTTP Provider Fixture (Node.js)
 * Serves OpenAI-compatible chat/completions endpoint.
 * Extends Phase 3 embedding/rerank fixtures.
 */
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const CHAT_PORT = 18082;

// --- Helper to send JSON response ---
function sendJson(res, code, data) {
    const body = JSON.stringify(data);
    res.writeHead(code, {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body)
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

// --- Deterministic normal response ---
function buildNormalResponse(model, content) {
    return {
        id: 'chatcmpl-' + crypto.randomUUID().slice(0, 8),
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: model || 'gpt-4o-mini',
        choices: [{
            index: 0,
            message: {
                role: 'assistant',
                content: content || 'This is a deterministic test response from IntelliDesk Phase 4 chat fixture.'
            },
            finish_reason: 'stop'
        }],
        usage: {
            prompt_tokens: 50,
            completion_tokens: 20,
            total_tokens: 70
        }
    };
}

// --- Deterministic rewrite response ---
function buildRewriteResponse(model, content) {
    return {
        id: 'chatcmpl-' + crypto.randomUUID().slice(0, 8),
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: model || 'gpt-4o-mini',
        choices: [{
            index: 0,
            message: {
                role: 'assistant',
                content: content || 'What are the travel policies for Beijing?'
            },
            finish_reason: 'stop'
        }],
        usage: {
            prompt_tokens: 80,
            completion_tokens: 15,
            total_tokens: 95
        }
    };
}

// --- Streaming tokens ---
function buildStreamTokens(model) {
    return [
        { content: 'This' },
        { content: ' is' },
        { content: ' a' },
        { content: ' deterministic' },
        { content: ' streaming' },
        { content: ' response' },
        { content: '.' }
    ];
}

function sendStreamToken(res, model, token, finish) {
    const chunk = {
        id: 'chatcmpl-' + crypto.randomUUID().slice(0, 8),
        object: 'chat.completion.chunk',
        created: Math.floor(Date.now() / 1000),
        model: model || 'gpt-4o-mini',
        choices: [{
            index: 0,
            delta: finish ? {} : { content: token },
            finish_reason: finish || null
        }]
    };
    res.write('data: ' + JSON.stringify(chunk) + '\n\n');
}

// --- Agent-mode deterministic tool-call fixture ---
// When the request carries the Phase 5 Agent system prompt, first ask for a real
// business tool (knowledge_base_list), and after the tool result is fed back return
// a deterministic final answer. This is a test/E2E fixture only — it keeps the real
// backend Agent tool orchestration in the loop without changing any API contract.
function isAgentRequest(messages) {
    const sys = messages ? messages.find(m => m.role === 'system') : null;
    return !!sys && !!sys.content && sys.content.includes('intelligent enterprise assistant with access to tools');
}

function hasToolResult(messages) {
    return messages ? messages.some(m => m.role === 'tool') : false;
}

function userText(messages) {
    return (messages || [])
        .filter(m => m.role === 'user' && typeof m.content === 'string')
        .map(m => m.content)
        .join('\n');
}

function buildAgentToolCallResponse(model) {
    return {
        id: 'chatcmpl-agent-' + crypto.randomUUID().slice(0, 8),
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: model || 'gpt-4o-mini',
        choices: [{
            index: 0,
            message: {
                role: 'assistant',
                content: null,
                tool_calls: [{
                    id: 'call_' + crypto.randomUUID().slice(0, 10),
                    type: 'function',
                    function: { name: 'knowledge_base_list', arguments: '{"page":1,"size":20}' }
                }]
            },
            finish_reason: 'tool_calls'
        }],
        usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 }
    };
}

// --- Chat Server (OpenAI-compatible chat/completions) ---
const chatServer = http.createServer(async (req, res) => {
    // CORS headers for testing
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');

    if (req.method === 'OPTIONS') {
        res.writeHead(200);
        res.end();
        return;
    }

    if (req.method === 'GET') {
        sendJson(res, 200, { status: 'ok', service: 'chat-stub' });
        return;
    }

    if (req.method !== 'POST' || req.url !== '/v1/chat/completions') {
        sendJson(res, 404, { error: { message: 'Not found', type: 'not_found' } });
        return;
    }

    // Auth check
    const auth = req.headers['authorization'] || '';
    if (!auth.startsWith('Bearer ')) {
        sendJson(res, 401, { error: { message: 'Missing API key', type: 'auth_error' } });
        return;
    }

    const apiKey = auth.slice(7);

    // Validate API key
    if (apiKey !== 'test-chat-key' && apiKey !== 'test-chat-failure-key' && apiKey !== 'test-chat-timeout-key' && apiKey !== 'test-chat-429-key' && apiKey !== 'test-chat-malformed-key') {
        sendJson(res, 401, { error: { message: 'Invalid API key', type: 'auth_error' } });
        return;
    }

    // Error simulation by API key
    if (apiKey === 'test-chat-failure-key') {
        sendJson(res, 500, { error: { message: 'Internal server error', type: 'server_error' } });
        return;
    }
    if (apiKey === 'test-chat-timeout-key') {
        await new Promise(r => setTimeout(r, 30000));
        sendJson(res, 504, { error: { message: 'Gateway timeout' } });
        return;
    }
    if (apiKey === 'test-chat-429-key') {
        sendJson(res, 429, { error: { message: 'Rate limit exceeded', type: 'rate_limit' } });
        return;
    }
    if (apiKey === 'test-chat-malformed-key') {
        // Return malformed JSON
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end('{this is not valid json}');
        return;
    }

    try {
        const body = JSON.parse(await readBody(req));
        const model = body.model || 'gpt-4o-mini';
        const stream = body.stream === true;
        const messages = body.messages || [];

        // Check if this is a query rewrite request (detect by system prompt content)
        const systemMsg = messages.find(m => m.role === 'system');
        const isRewrite = systemMsg && systemMsg.content && systemMsg.content.includes('query rewriter');

        if (stream) {
            // Streaming response
            res.writeHead(200, {
                'Content-Type': 'text/event-stream',
                'Cache-Control': 'no-cache',
                'Connection': 'keep-alive'
            });

            // Test/E2E fixture markers (keyed on user message text; no API contract change):
            // "[disconnect]" -> write a couple tokens then abruptly close the socket, so the
            // client sees an unexpected EOF before the stream completes. This exercises the
            // frontend disconnect path (must NOT auto re-POST).
            if (userText(messages).includes('[disconnect]')) {
                const pre = [{ content: 'Partial' }, { content: ' answer' }];
                for (let i = 0; i < pre.length; i++) {
                    sendStreamToken(res, model, pre[i].content, null);
                    await new Promise(r => setTimeout(r, 10));
                }
                res.destroy();
                console.warn('[stub] simulated mid-stream disconnect (no done sent)');
                return;
            }

            // RAG-grounded reply: when the backend supplies a non-empty retrieval
            // context block, return a deterministic answer that cites the top chunk.
            // This is a test/E2E fixture only — it does NOT change any API contract
            // and keeps the real backend retrieval/citation pipeline in the loop.
            const sysContent = systemMsg ? (systemMsg.content || '') : '';
            const hasRagContext = sysContent.includes('Context:')
                && sysContent.includes('[1]')
                && sysContent.includes('Source:');

            const tokens = hasRagContext
                ? [
                    { content: 'According' },
                    { content: ' to' },
                    { content: ' the' },
                    { content: ' knowledge' },
                    { content: ' base,' },
                    { content: ' the' },
                    { content: ' Beijing' },
                    { content: ' daily' },
                    { content: ' allowance' },
                    { content: ' is' },
                    { content: '600' },
                    { content: ' yuan.' },
                    { content: '[1]' },
                    { content: '' }
                ]
                : buildStreamTokens(model);

            for (let i = 0; i < tokens.length; i++) {
                const isLast = i === tokens.length - 1;
                sendStreamToken(res, model, tokens[i].content, isLast ? 'stop' : null);
                await new Promise(r => setTimeout(r, 10));
            }

            res.write('data: [DONE]\n\n');
            res.end();
        } else {
            // Non-streaming response

            // Test/E2E fixture marker: "[error]" in the user text -> provider 500, so the
            // real backend turns it into an SSE `error` event (sanitized) over the open stream.
            if (userText(messages).includes('[error]')) {
                sendJson(res, 500, { error: { message: 'Simulated provider failure for error-path E2E', type: 'server_error' } });
                return;
            }

            if (isAgentRequest(messages) && !hasToolResult(messages)) {
                // Agent tool-call turn: ask the backend to call a real business tool.
                // Test/E2E fixture only — keeps the real backend Agent tool
                // orchestration in the loop; does not change any API contract.
                sendJson(res, 200, buildAgentToolCallResponse(model));
            } else if (isAgentRequest(messages)) {
                // Agent final turn after the tool result was fed back to the LLM.
                sendJson(res, 200, buildNormalResponse(model,
                    'I listed the available knowledge bases through the knowledge_base_list tool. '
                    + 'Ask me about documents in this workspace.'));
            } else if (isRewrite) {
                // Extract the current question from the last user message
                const lastUserMsg = [...messages].reverse().find(m => m.role === 'user');
                const currentQuestion = lastUserMsg ? lastUserMsg.content : '';
                const rewritten = 'Standalone rewritten: ' + currentQuestion;
                sendJson(res, 200, buildRewriteResponse(model, rewritten));
            } else {
                sendJson(res, 200, buildNormalResponse(model, null));
            }
        }
    } catch (e) {
        sendJson(res, 400, { error: { message: 'Invalid request: ' + e.message, type: 'invalid_request' } });
    }
});

// --- Start server ---
chatServer.listen(CHAT_PORT, '127.0.0.1', () => {
    console.log(`[chat]      listening on http://127.0.0.1:${CHAT_PORT}`);
});

console.log('='.repeat(60));
console.log('IntelliDesk Phase 4 E2E — Deterministic Chat HTTP Provider Fixture');
console.log('='.repeat(60));
console.log(`Chat: http://127.0.0.1:${CHAT_PORT}/v1/chat/completions`);
console.log('='.repeat(60));
console.log('Special API keys for testing:');
console.log('  test-chat-failure-key  -> 500 error');
console.log('  test-chat-timeout-key  -> 30s timeout');
console.log('  test-chat-429-key      -> 429 rate limit');
console.log('  test-chat-malformed-key -> malformed response');
console.log('='.repeat(60));

// Keep process alive
process.on('SIGINT', () => {
    console.log('\nShutting down...');
    chatServer.close();
    process.exit(0);
});