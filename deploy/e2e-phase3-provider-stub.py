"""
IntelliDesk Phase 3 E2E — Deterministic HTTP Provider Fixtures
Serves OpenAI-compatible embedding and DashScope-compatible rerank endpoints.
Uses deterministic hashing; no external API calls.
"""
import json
import hashlib
import struct
import time
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler

# --- Configuration ---
EMBEDDING_PORT = 18080
RERANK_PORT = 18081
EMBEDDING_DIM = 1536

# --- Deterministic Embedding Generator ---
def deterministic_embedding(text: str) -> list[float]:
    """Generate a deterministic 1536-dim embedding from text."""
    h = hashlib.sha256(text.encode('utf-8')).digest()
    vec = []
    for i in range(EMBEDDING_DIM):
        # Use 4 bytes of hash per float, cycling through the hash
        offset = (i * 4) % len(h)
        val = struct.unpack('>f', h[offset:offset+4])[0]
        # Clamp to reasonable range
        val = max(-1.0, min(1.0, val))
        vec.append(round(val, 6))
    return vec


# --- Deterministic Rerank Scorer ---
def deterministic_rerank_score(query: str, document: str) -> float:
    """Score based on token overlap between query and document."""
    query_tokens = set(query.lower().split())
    doc_tokens = set(document.lower().split())
    if not query_tokens:
        return 0.0
    overlap = len(query_tokens & doc_tokens)
    total = len(query_tokens)
    # Base score: 0.3–0.95 range based on overlap
    base = 0.3 + 0.65 * (overlap / total)
    # Add small hash-based variation for tie-breaking
    h = hashlib.md5((query + document).encode('utf-8')).digest()
    jitter = struct.unpack('>B', h[0:1])[0] / 2560.0  # 0.0–0.1 jitter
    return round(base + jitter, 6)


# ================================================================
# Embedding Handler (OpenAI-compatible)
# ================================================================
class EmbeddingHandler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, format, *args):
        print(f"[embedding] {format % args}")

    def _send_json(self, code: int, data: dict):
        body = json.dumps(data).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != '/v1/embeddings':
            self._send_json(404, {"error": {"message": "Not found", "type": "not_found"}})
            return

        # Check auth
        auth = self.headers.get('Authorization', '')
        if not auth.startswith('Bearer '):
            self._send_json(401, {"error": {"message": "Missing API key", "type": "auth_error"}})
            return

        api_key = auth[7:]
        if api_key == 'test-failure-key':
            self._send_json(500, {"error": {"message": "Internal server error", "type": "server_error"}})
            return
        if api_key == 'test-timeout-key':
            time.sleep(30)
            self._send_json(504, {"error": {"message": "Gateway timeout"}})
            return
        if api_key == 'test-429-key':
            self._send_json(429, {"error": {"message": "Rate limit exceeded", "type": "rate_limit"}})
            return

        content_length = int(self.headers.get('Content-Length', 0))
        body = json.loads(self.rfile.read(content_length))

        model = body.get('model', '')
        inputs = body.get('input', [])
        if isinstance(inputs, str):
            inputs = [inputs]

        data = []
        for idx, text in enumerate(inputs):
            embedding = deterministic_embedding(text)
            data.append({
                "object": "embedding",
                "index": idx,
                "embedding": embedding
            })

        resp = {
            "object": "list",
            "data": data,
            "model": model,
            "usage": {
                "prompt_tokens": sum(len(t.split()) for t in inputs),
                "total_tokens": sum(len(t.split()) for t in inputs)
            }
        }
        self._send_json(200, resp)

    def do_GET(self):
        self._send_json(200, {"status": "ok", "service": "embedding-stub"})


# ================================================================
# Rerank Handler (DashScope-compatible)
# ================================================================
class RerankHandler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, format, *args):
        print(f"[rerank] {format % args}")

    def _send_json(self, code: int, data: dict):
        body = json.dumps(data).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        # Handle both DashScope paths
        valid_paths = [
            '/api/v1/services/rerank/text-rerank/text-rerank',
            '/compatible-mode/v1/rerank'
        ]
        if self.path not in valid_paths:
            self._send_json(404, {"error": {"message": "Not found", "type": "not_found"}})
            return

        # Check auth
        auth = self.headers.get('Authorization', '')
        if not auth.startswith('Bearer '):
            self._send_json(401, {"error": {"message": "Missing API key", "type": "auth_error"}})
            return

        api_key = auth[7:]
        if api_key == 'test-rerank-failure-key':
            self._send_json(500, {"error": {"message": "Internal server error", "type": "server_error"}})
            return
        if api_key == 'test-rerank-timeout-key':
            time.sleep(30)
            self._send_json(504, {"error": {"message": "Gateway timeout"}})
            return
        if api_key == 'test-rerank-429-key':
            self._send_json(429, {"error": {"message": "Rate limit exceeded", "type": "rate_limit"}})
            return

        content_length = int(self.headers.get('Content-Length', 0))
        body = json.loads(self.rfile.read(content_length))

        model = body.get('model', '')
        inp = body.get('input', {})
        query = inp.get('query', '')
        documents = inp.get('documents', [])

        results = []
        for idx, doc in enumerate(documents):
            score = deterministic_rerank_score(query, doc)
            results.append({
                "index": idx,
                "relevance_score": score
            })

        # Sort by relevance_score desc
        results.sort(key=lambda r: r['relevance_score'], reverse=True)

        resp = {
            "results": results,
            "model": model,
            "usage": {
                "total_tokens": sum(len(d.split()) for d in documents) + len(query.split())
            }
        }
        self._send_json(200, resp)

    def do_GET(self):
        self._send_json(200, {"status": "ok", "service": "rerank-stub"})


# ================================================================
# Server Launcher
# ================================================================
def run_server(port: int, handler_class, name: str):
    server = HTTPServer(('127.0.0.1', port), handler_class)
    print(f"[{name}] listening on http://127.0.0.1:{port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        print(f"[{name}] stopped")


if __name__ == '__main__':
    print("=" * 60)
    print("IntelliDesk Phase 3 E2E — Deterministic HTTP Provider Fixtures")
    print("=" * 60)
    print(f"Embedding: http://127.0.0.1:{EMBEDDING_PORT}/v1/embeddings")
    print(f"Rerank:    http://127.0.0.1:{RERANK_PORT}/api/v1/services/rerank/text-rerank/text-rerank")
    print("=" * 60)

    t1 = threading.Thread(target=run_server, args=(EMBEDDING_PORT, EmbeddingHandler, 'embedding'), daemon=True)
    t2 = threading.Thread(target=run_server, args=(RERANK_PORT, RerankHandler, 'rerank'), daemon=True)
    t1.start()
    t2.start()

    try:
        while t1.is_alive() or t2.is_alive():
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nShutting down...")