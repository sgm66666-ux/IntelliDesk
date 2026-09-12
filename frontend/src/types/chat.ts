import type { CitationItem } from '@/types/conversation';

export type ChatMode = 'rag' | 'agent';

/** SSE event names emitted by the backend (SseEventType). */
export const SseEventName = {
  start: 'start',
  token: 'token',
  citation: 'citation',
  usage: 'usage',
  done: 'done',
  error: 'error',
  tool_call: 'tool_call',
  tool_result: 'tool_result',
} as const;
export type SseEventName = (typeof SseEventName)[keyof typeof SseEventName];

export interface SseStartEvent {
  requestId?: string;
  conversationId?: number;
  messageId?: number;
}

export interface SseTokenEvent {
  delta: string;
}

export interface SseUsageEvent {
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
}

export interface SseDoneEvent {
  messageId?: number;
  finishReason?: string;
}

export interface SseErrorEvent {
  code?: string;
  message?: string;
}

export interface SseToolCallEvent {
  toolName: string;
  step?: number;
}

export interface SseToolResultEvent {
  toolName: string;
  success: boolean;
  resultCount?: number;
  durationMs?: number;
}

export interface ToolTraceItem {
  key: number;
  toolName: string;
  step?: number;
  status: 'running' | 'done' | 'error';
  success?: boolean;
  resultCount?: number;
  durationMs?: number;
}

/** A single parsed SSE event (buffered-framing safe). */
export interface ParsedSseEvent {
  event: string;
  data: string;
  id?: string;
}

/** Unified callbacks the chat page observes during a stream. */
export interface ChatStreamHandlers {
  onStart?: (data: SseStartEvent) => void;
  onToken?: (data: SseTokenEvent) => void;
  onCitations?: (data: CitationItem[]) => void;
  onUsage?: (data: SseUsageEvent) => void;
  onToolCall?: (data: SseToolCallEvent) => void;
  onToolResult?: (data: SseToolResultEvent) => void;
  /** Terminal success. */
  onDone?: (data: SseDoneEvent) => void;
  /** Server-side SSE error event. */
  onError?: (data: SseErrorEvent) => void;
  /** Connection dropped / EOF before done, or HTTP-level failure. */
  onDisconnect?: (reason: string) => void;
}