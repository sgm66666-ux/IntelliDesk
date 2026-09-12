import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  streamRagChat: vi.fn(),
  streamAgentChat: vi.fn(),
}));

vi.mock('@/api/chat', () => mocks);

import { setActivePinia, createPinia } from 'pinia';
import { useChatStore } from '@/stores/chat';
import type { ChatStreamHandlers } from '@/types/chat';

interface Capture {
  handlers: ChatStreamHandlers;
  abort: ReturnType<typeof vi.fn>;
}

function installStreamMock() {
  const captures: Capture[] = [];
  // Wire the fake abort() to the AbortSignal the store passes in, so that
  // store.stop() -> controller.abort() propagates to the mock (& its abort fn).
  const mk = (_w: number, _c: number, _b: object, opts: { signal?: AbortSignal; handlers: ChatStreamHandlers }) => {
    const cap = { handlers: opts.handlers, abort: vi.fn() };
    opts.signal?.addEventListener('abort', cap.abort);
    captures.push(cap);
    return { abort: cap.abort };
  };
  mocks.streamAgentChat.mockImplementation(mk);
  mocks.streamRagChat.mockImplementation(mk);
  return captures;
}

function sendArgs(conversationId = 1, query = 'A', handlers: ChatStreamHandlers = {}) {
  return { workspaceId: 7, conversationId, mode: 'agent' as const, query, handlers };
}

describe('chat store — streaming gates', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it('accepts one active stream and ignores a second send', () => {
    const captures = installStreamMock();
    const store = useChatStore();

    expect(store.send(sendArgs(1, 'A'))).toBe(true);
    expect(store.isStreaming).toBe(true);
    expect(store.activeConversationId).toBe(1);

    const started2 = store.send(sendArgs(1, 'B'));
    expect(started2).toBe(false); // duplicate send ignored
    expect(mocks.streamAgentChat).toHaveBeenCalledTimes(1); // no second HTTP request

    captures[0].handlers.onDone?.({});
    expect(store.isStreaming).toBe(false);
  });

  it('does not auto re-POST when a stream connection is lost', () => {
    const captures = installStreamMock();
    const store = useChatStore();

    store.send(sendArgs(1, 'A'));
    expect(store.isStreaming).toBe(true);

    // simulate connection loss mid-stream
    captures[0].handlers.onDisconnect?.('network down');

    // exactly one request issued, never a silent replay
    expect(mocks.streamAgentChat).toHaveBeenCalledTimes(1);
    expect(store.isStreaming).toBe(false);
    expect(store.disconnectDetected).toBe(true);
    expect(store.streamError?.message).toBe('network down');
  });

  it('stop() aborts the controller, drops later tokens, and allows a fresh send', () => {
    const captures = installStreamMock();
    const store = useChatStore();
    let tokens = 0;
    const handlers = { onToken: () => tokens++ };

    store.send(sendArgs(1, 'A', handlers));
    const abortMock = captures[0].abort;

    store.stop();
    expect(abortMock).toHaveBeenCalledOnce();
    expect(store.isStreaming).toBe(false);

    // any late token after stop is dropped by the stale guard
    captures[0].handlers.onToken?.({ delta: 'late' });
    expect(tokens).toBe(0);

    // user can send a new message afterward
    expect(store.send(sendArgs(1, 'B'))).toBe(true);
    expect(mocks.streamAgentChat).toHaveBeenCalledTimes(2);
  });

  it('guards stale events from a previous conversation', () => {
    const captures = installStreamMock();
    const store = useChatStore();
    let conv1Tokens = 0;
    let conv2Tokens = 0;

    store.send(sendArgs(1, 'A', { onToken: () => conv1Tokens++ }));
    store.stop(); // user switches conversation
    store.send(sendArgs(2, 'B', { onToken: () => conv2Tokens++ }));

    // late token from conversation 1 must never reach conv2's handlers
    captures[0].handlers.onToken?.({ delta: 'late' });
    expect(conv1Tokens).toBe(0);
    expect(conv2Tokens).toBe(0);
  });

  it('routes RAG mode to the RAG streaming API with selected KB ids', async () => {
    const captures = installStreamMock();
    const store = useChatStore();
    const kbIds = [5, 6];

    store.send({ workspaceId: 7, conversationId: 1, mode: 'rag', query: 'Q', kbIds, handlers: {} });

    expect(mocks.streamRagChat).toHaveBeenCalledTimes(1);
    const [, , body] = mocks.streamRagChat.mock.calls[0] as unknown as [number, number, { knowledgeBaseIds: number[] }];
    expect(body.knowledgeBaseIds).toEqual([5, 6]);
    expect(store.activeMode).toBe('rag');
    void captures;
  });
});