import { defineStore } from 'pinia';
import { ref } from 'vue';
import * as chatApi from '@/api/chat';
import type { ChatMode, ChatStreamHandlers } from '@/types/chat';

/**
 * Chat streaming store.
 *
 * Hard guarantees implemented here:
 *  - At most ONE active streaming request at a time. Sending while streaming
 *    is ignored (never issues a second HTTP request).
 *  - NO auto re-POST: a stream that fails mid-way is never resubmitted. The UI
 *    recovers by reloading the persisted conversation state instead.
 *  - AbortController-backed Stop.
 *  - Stale-response guard: every event is checked against the session epoch,
 *    active conversation id and active mode before it is applied, so a late
 *    event from a previous route / conversation is dropped.
 *
 * AbortController is deliberately held at module scope (runtime only) — it is
 * never serialized or persisted.
 */
export const useChatStore = defineStore('chat', () => {
  const isStreaming = ref(false);
  const activeConversationId = ref<number | null>(null);
  const activeMode = ref<ChatMode | null>(null);
  const streamError = ref<{ code?: string; message: string } | null>(null);
  const disconnectDetected = ref(false);

  let sessionEpoch = 0;
  let activeController: AbortController | null = null;

  function clearStreamState(): void {
    isStreaming.value = false;
    activeController = null;
    // keep activeConversationId/activeMode for guard semantics until next send
  }

  function stop(): void {
    sessionEpoch += 1; // invalidate in-flight events
    if (activeController) {
      activeController.abort();
      activeController = null;
    }
    isStreaming.value = false;
    streamError.value = null;
  }

  function resetSession(): void {
    stop();
    activeConversationId.value = null;
    activeMode.value = null;
    disconnectDetected.value = false;
  }

  interface SendParams {
    workspaceId: number;
    conversationId: number;
    mode: ChatMode;
    query: string;
    kbIds?: number[];
    handlers: ChatStreamHandlers;
  }

  /**
   * Start one stream. Returns true if a request was dispatched, false if a
   * request is already in flight (duplicate send / Enter spam are ignored).
   */
  function send(params: SendParams): boolean {
    if (isStreaming.value) return false; // single active request gate

    const sessionId = ++sessionEpoch;
    const { workspaceId, conversationId, mode, query, kbIds, handlers } = params;
    activeConversationId.value = conversationId;
    activeMode.value = mode;
    isStreaming.value = true;
    streamError.value = null;
    disconnectDetected.value = false;

    const controller = new AbortController();
    activeController = controller;

    // Wrap every handler with the stale guard so late responses for an older
    // conversation / mode are never applied to the current view.
    const guard = <T>(fn?: (data: T) => void) => (data: T) => {
      if (sessionId !== sessionEpoch) return;
      if (activeConversationId.value !== conversationId) return;
      if (activeMode.value !== mode) return;
      fn?.(data);
    };

    const guarded: ChatStreamHandlers = {
      onStart: guard(handlers.onStart),
      onToken: guard(handlers.onToken),
      onCitations: guard(handlers.onCitations),
      onUsage: guard(handlers.onUsage),
      onToolCall: guard(handlers.onToolCall),
      onToolResult: guard(handlers.onToolResult),
      onDone: guard((data) => {
        handlers.onDone?.(data);
        sessionEpoch += 1; // finalize session
        clearStreamState();
      }),
      onError: guard((data) => {
        handlers.onError?.(data);
        sessionEpoch += 1;
        streamError.value = {
          code: data.code,
          message: data.message || 'Chat provider error',
        };
        clearStreamState();
      }),
      onDisconnect: guard((reason) => {
        // Connection lost: do NOT re-POST. Surface a recoverable error.
        disconnectDetected.value = true;
        streamError.value = {
          message: reason && reason !== 'stream ended'
            ? reason
            : 'Connection lost before the answer completed',
        };
        handlers.onDisconnect?.(reason);
        sessionEpoch += 1;
        clearStreamState();
      }),
    };

    try {
      if (mode === 'rag') {
        chatApi.streamRagChat(workspaceId, conversationId, {
          query,
          knowledgeBaseIds: kbIds ?? [],
        }, { signal: controller.signal, handlers: guarded });
      } else {
        chatApi.streamAgentChat(workspaceId, conversationId, {
          query,
        }, { signal: controller.signal, handlers: guarded });
      }
    } catch {
      // Synchronous wiring failure — never auto-retry.
      isStreaming.value = false;
      activeController = null;
      streamError.value = { message: 'Failed to start chat stream' };
      return true;
    }

    return true;
  }

  return {
    isStreaming,
    activeConversationId,
    activeMode,
    streamError,
    disconnectDetected,
    send,
    stop,
    resetSession,
  };
});