import { describe, expect, it, vi, beforeEach } from 'vitest';
import { streamAgentChat } from '@/api/chat';
import type { ChatStreamHandlers } from '@/types/chat';

/** Fake ReadableStreamDefaultReader that yields the given UTF-8 chunks. */
function readerFrom(chunks: string[]) {
  const encoder = new TextEncoder();
  let i = 0;
  return {
    async read() {
      if (i < chunks.length) {
        const value = encoder.encode(chunks[i++]);
        return { done: false as const, value };
      }
      return { done: true as const, value: undefined };
    },
    cancel() {},
  };
}

function mockFetchStream(chunks: string[], ok = true, contentType = 'text/event-stream') {
  const fetchMock = vi.fn().mockResolvedValue({
    ok,
    headers: { get: (name: string) => (name.toLowerCase() === 'content-type' ? contentType : null) },
    body: ok ? { getReader: () => readerFrom(chunks) } : null,
    status: ok ? 200 : 500,
    text: async () => JSON.stringify({ code: 500, message: 'boom', traceId: 't' }),
  });
  (globalThis as any).fetch = fetchMock;
  return fetchMock;
}

async function flush() {
  await new Promise((r) => setTimeout(r, 0));
}

describe('chat SSE client', () => {
  beforeEach(() => {
    (window as any).__INJECT_ACCESS_TOKEN__ = () => 'test-access-token';
  });

  it('emits tokens, then done, using exactly one POST', async () => {
    // a single token event split across 2 chunks, then a done event
    const chunks = [
      'event: start\ndata: {"requestId":"r1","conversationId":2,"messageId":9}\n\n',
      'event: token\ndata: {"delta":"hel',
      'lo"}\n\nevent: done\ndata: {"messageId":9,"finishReason":"stop"}\n\n',
    ];
    const fetchMock = mockFetchStream(chunks);

    const events: string[] = [];
    const tokens: string[] = [];
    let done = false;
    const handlers: ChatStreamHandlers = {
      onStart() {
        events.push('start');
      },
      onToken: ({ delta }) => tokens.push(delta),
      onDone: () => {
        done = true;
        events.push('done');
      },
    };

    let handle!: { abort: () => void };
    handle = streamAgentChat(1, 2, { query: 'hi' }, { signal: new AbortController().signal, handlers });

    // wait for stream to finish
    for (let i = 0; i < 20 && !done; i++) await flush();

    expect(fetchMock).toHaveBeenCalledTimes(1); // never re-submits
    expect(fetchMock.mock.calls[0][0]).toBe('/api/workspaces/1/conversations/2/agent/stream');
    expect(events).toEqual(['start', 'done']);
    expect(tokens).toEqual(['hello']);
    void handle;
  });

  it('surfaces an HTTP error without emitting a second request', async () => {
    const fetchMock = mockFetchStream([], false);

    let disconnected = '';
    const handlers: ChatStreamHandlers = {
      onDisconnect: (reason) => {
        disconnected = reason;
      },
    };

    streamAgentChat(1, 2, { query: 'hi' }, { signal: new AbortController().signal, handlers });

    for (let i = 0; i < 20 && !disconnected; i++) await flush();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    // backend-envelope message is surfaced (sanitized), never a raw stack
    expect(disconnected).toBe('boom');
  });

  it('flushes a final partial token before EOF', async () => {
    // no trailing blank line on last event — still delivered via flush()
    const chunks = ['event: token\ndata: {"delta":"final"}'];
    mockFetchStream(chunks);

    const tokens: string[] = [];
    let ended = false;
    const handlers: ChatStreamHandlers = {
      onToken: ({ delta }) => tokens.push(delta),
      onDisconnect: () => {
        ended = true;
      },
    };

    streamAgentChat(3, 4, { query: 'q' }, { signal: new AbortController().signal, handlers });
    for (let i = 0; i < 20 && !ended; i++) await flush();

    expect(tokens).toEqual(['final']);
  });
});