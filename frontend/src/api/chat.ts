import { connectSse, type SseClientHandle } from '@/lib/sse';
import type { CitationItem } from '@/types/conversation';
import type {
  ChatStreamHandlers,
  SseStartEvent,
  SseTokenEvent,
  SseUsageEvent,
  SseDoneEvent,
  SseErrorEvent,
  SseToolCallEvent,
  SseToolResultEvent,
  ParsedSseEvent,
} from '@/types/chat';

const basePath = (workspaceId: number, conversationId: number) =>
  `/api/workspaces/${workspaceId}/conversations/${conversationId}`;

/**
 * Resolve the runtime access token for streaming requests.
 * The shared auth client keeps the token in memory only (no Web Storage).
 */
function authHeader(): Record<string, string> {
  const token = (window as any).__INJECT_ACCESS_TOKEN__?.();
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

function parseJson<T>(data: string): T | null {
  try {
    return JSON.parse(data) as T;
  } catch {
    return null;
  }
}

/** Route a parsed SSE event to the matching handler; returns true if terminal. */
function dispatch(ev: ParsedSseEvent, handlers: ChatStreamHandlers): boolean {
  switch (ev.event) {
    case 'start': {
      const payload = parseJson<SseStartEvent>(ev.data);
      handlers.onStart?.(payload ?? {});
      return false;
    }
    case 'token': {
      const payload = parseJson<SseTokenEvent>(ev.data);
      if (payload) handlers.onToken?.(payload);
      return false;
    }
    case 'citation': {
      const payload = parseJson<CitationItem[]>(ev.data);
      if (Array.isArray(payload)) handlers.onCitations?.(payload);
      return false;
    }
    case 'usage': {
      const payload = parseJson<SseUsageEvent>(ev.data);
      if (payload) handlers.onUsage?.(payload);
      return false;
    }
    case 'tool_call': {
      const payload = parseJson<SseToolCallEvent>(ev.data);
      if (payload) handlers.onToolCall?.(payload);
      return false;
    }
    case 'tool_result': {
      const payload = parseJson<SseToolResultEvent>(ev.data);
      if (payload) handlers.onToolResult?.(payload);
      return false;
    }
    case 'done': {
      const payload = parseJson<SseDoneEvent>(ev.data);
      handlers.onDone?.(payload ?? {});
      return true;
    }
    case 'error': {
      const payload = parseJson<SseErrorEvent>(ev.data);
      handlers.onError?.(payload ?? {});
      return true;
    }
    default:
      return false;
  }
}

export interface ChatStreamOptions {
  signal: AbortSignal;
  handlers: ChatStreamHandlers;
}

function openStream(
  url: string,
  body: object,
  options: ChatStreamOptions
): SseClientHandle {
  let handle: SseClientHandle = { abort: () => {} };
  handle = connectSse({
    url,
    method: 'POST',
    body,
    headers: authHeader(),
    signal: options.signal,
    onEvent: (ev) => {
      try {
        const terminal = dispatch(ev, options.handlers);
        if (terminal) handle.abort();
      } catch {
        /* never let a single malformed event crash the stream */
      }
    },
    onStreamEnded: () => options.handlers.onDisconnect?.('stream ended'),
    onHttpError: (err) => {
      const message = err instanceof Error ? err.message : 'Network error';
      options.handlers.onDisconnect?.(message);
    },
  });
  return handle;
}

/** RAG Chat SSE (POST). body: { query, knowledgeBaseIds, ... } */
export function streamRagChat(
  workspaceId: number,
  conversationId: number,
  body: {
    query: string;
    knowledgeBaseIds: number[];
    documentIds?: number[];
    rewriteEnabled?: boolean;
    candidateTopK?: number;
    topK?: number;
  },
  options: ChatStreamOptions
): SseClientHandle {
  const url = basePath(workspaceId, conversationId) + '/messages/stream';
  return openStream(url, body, options);
}

/** Agent Chat SSE (POST). body: { query } */
export function streamAgentChat(
  workspaceId: number,
  conversationId: number,
  body: { query: string },
  options: ChatStreamOptions
): SseClientHandle {
  const url = basePath(workspaceId, conversationId) + '/agent/stream';
  return openStream(url, body, options);
}