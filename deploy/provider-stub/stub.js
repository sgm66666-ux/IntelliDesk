/**
 * IntelliDesk Wave 5 E2E — combined deterministic OpenAI-compatible provider stub.
 * Serves on 0.0.0.0:18080 (container-internal) so the containerized backend can
 * reach it over the Docker network. E2E infrastructure ONLY; never used in prod.
 *
 * Routes:
 *   POST /v1/chat/completions  -> streaming & non-streaming, incl. RAG citation +
 *                                 Agent (knowledge_base_list) tool-call fixture
 *   POST /v1/embeddings        -> deterministic 1536-dim embeddings
 */
const http = require('http');
const crypto = require('crypto');

const PORT = 18080;
const DIM = 1536;

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
    req.on('data', (c) => (body += c));
    req.on('end', () => resolve(body));
  });
}

function userText(messages) {
  return (messages || [])
    .filter((m) => m.role === 'user' && typeof m.content === 'string')
    .map((m) => m.content)
    .join('\n');
}

function isAgentRequest(messages) {
  const sys = (messages || []).find((m) => m.role === 'system');
  return !!sys && !!sys.content && sys.content.includes('intelligent enterprise assistant with access to tools');
}

// The backend's QueryRewriteService asks the provider to rewrite the user question.
// The stub echoes the exact "Current Question" so the rewritten query keeps the real
// search text (retrieval matches the deterministic fixture). Otherwise the generic
// default answer would corrupt the retrieval query and produce empty RAG citations.
function extractRewriteQuestion(messages) {
  const sys = (messages || []).find((m) => m.role === 'system');
  if (!sys || !sys.content) return null;
  if (!sys.content.includes('query rewriter') || !sys.content.includes('Rewritten Question:')) return null;
  const startMarker = 'Current Question: ';
  const endMarker = '\n\nRewritten Question:';
  const start = sys.content.indexOf(startMarker);
  const end = sys.content.indexOf(endMarker);
  if (start < 0) return null;
  if (end < 0) return sys.content.slice(start + startMarker.length).trim();
  return sys.content.slice(start + startMarker.length, end).trim();
}

function hasToolResult(messages) {
  return (messages || []).some((m) => m.role === 'tool');
}

function id(prefix) {
  return prefix + crypto.randomUUID().slice(0, 8);
}

function sendStreamToken(res, model, token, finish) {
  const chunk = {
    id: id('chatcmpl-'),
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model: model || 'gpt-4o-mini',
    choices: [{ index: 0, delta: finish ? {} : { content: token }, finish_reason: finish || null }]
  };
  res.write('data: ' + JSON.stringify(chunk) + '\n\n');
}

function deterministicEmbedding(text) {
  const hash = crypto.createHash('sha256').update(text, 'utf-8').digest();
  const vec = [];
  for (let i = 0; i < DIM; i++) {
    const offset = (i * 4) % hash.length;
    const val = hash.readFloatBE(offset);
    vec.push(Math.round(Math.max(-1.0, Math.min(1.0, val)) * 1000000) / 1000000);
  }
  return vec;
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET') {
    sendJson(res, 200, { status: 'ok', service: 'wave5-provider-stub' });
    return;
  }
  try {
    const body = JSON.parse(await readBody(req));

    if (req.method === 'POST' && req.url === '/v1/embeddings') {
      let inputs = body.input || [];
      if (typeof inputs === 'string') inputs = [inputs];
      sendJson(res, 200, {
        object: 'list',
        data: inputs.map((t, i) => ({ object: 'embedding', index: i, embedding: deterministicEmbedding(String(t)) })),
        model: body.model || '',
        usage: { prompt_tokens: 1, total_tokens: 1 }
      });
      return;
    }

    if (req.method === 'POST' && req.url === '/v1/chat/completions') {
      const model = body.model || 'gpt-4o-mini';
      const messages = body.messages || [];
      const stream = body.stream === true;

      if (stream) {
        res.writeHead(200, {
          'Content-Type': 'text/event-stream',
          'Cache-Control': 'no-cache',
          'Connection': 'keep-alive'
        });
        const sys = messages.find((m) => m.role === 'system');
        const sysContent = sys ? (sys.content || '') : '';
        const hasRagContext = sysContent.includes('Context:') && sysContent.includes('[1]') && sysContent.includes('Source:');
        const tokens = hasRagContext
          ? ['According', ' to', ' the', ' knowledge', ' base,', ' the', ' Beijing', ' daily', ' allowance', ' is', '600', ' yuan.', '[1]', '']
          : ['This', ' is', ' a', ' deterministic', ' streaming', ' response', '.'];
        for (let i = 0; i < tokens.length; i++) {
          sendStreamToken(res, model, tokens[i], i === tokens.length - 1 ? 'stop' : null);
          await new Promise((r) => setTimeout(r, 10));
        }
        res.write('data: [DONE]\n\n');
        res.end();
        return;
      }

      // Non-stream
      if (userText(messages).includes('[error]')) {
        sendJson(res, 500, { error: { message: 'Simulated provider failure', type: 'server_error' } });
        return;
      }
      // Query rewrite call: echo the original "Current Question" unchanged so the
      // downstream retrieval uses the real search text (deterministic E2E behavior).
      const rewriteQuestion = extractRewriteQuestion(messages);
      if (rewriteQuestion !== null) {
        sendJson(res, 200, {
          id: id('chatcmpl-rewrite-'),
          object: 'chat.completion',
          created: Math.floor(Date.now() / 1000),
          model,
          choices: [{
            index: 0,
            message: { role: 'assistant', content: rewriteQuestion },
            finish_reason: 'stop'
          }],
          usage: { prompt_tokens: 10, completion_tokens: 4, total_tokens: 14 }
        });
        return;
      }
      if (isAgentRequest(messages) && !hasToolResult(messages)) {
        sendJson(res, 200, {
          id: id('chatcmpl-agent-'),
          object: 'chat.completion',
          created: Math.floor(Date.now() / 1000),
          model,
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
        });
        return;
      }
      if (isAgentRequest(messages) && hasToolResult(messages)) {
        sendJson(res, 200, {
          id: id('chatcmpl-final-'),
          object: 'chat.completion',
          created: Math.floor(Date.now() / 1000),
          model,
          choices: [{
            index: 0,
            message: {
              role: 'assistant',
              content: 'I listed the available knowledge bases through the knowledge_base_list tool. Ask me about documents in this workspace.'
            },
            finish_reason: 'stop'
          }],
          usage: { prompt_tokens: 10, completion_tokens: 8, total_tokens: 18 }
        });
        return;
      }
      // Default deterministic normal answer
      sendJson(res, 200, {
        id: id('chatcmpl-'),
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model,
        choices: [{
          index: 0,
          message: { role: 'assistant', content: 'This is a deterministic test response from the Wave 5 provider stub.' },
          finish_reason: 'stop'
        }],
        usage: { prompt_tokens: 50, completion_tokens: 20, total_tokens: 70 }
      });
      return;
    }

    sendJson(res, 404, { error: { message: 'Not found', type: 'not_found' } });
  } catch (e) {
    sendJson(res, 400, { error: { message: 'Invalid request: ' + e.message, type: 'invalid_request' } });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('[wave5-provider-stub] listening on 0.0.0.0:' + PORT);
});