import type { ChatMessage, CitationItem, TokenUsageItem } from '@/types/conversation';

/**
 * Parse a jsonb cell that the backend returns as a compact JSON string
 * (e.g. `"[{...}]"`), while tolerating the case where it is already an array
 * / object (or null). Falls back to an empty array gracefully on invalid data.
 */
export function parseCitationField(message: ChatMessage): CitationItem[] {
  if (!message.citation) return [];
  if (Array.isArray(message.citation)) return message.citation as CitationItem[];
  if (typeof message.citation !== 'string') return [];
  try {
    const parsed = JSON.parse(message.citation);
    return Array.isArray(parsed) ? (parsed as CitationItem[]) : [];
  } catch {
    return [];
  }
}

export function parseUsageField(message: ChatMessage): TokenUsageItem | null {
  if (!message.tokenUsage) return null;
  if (typeof message.tokenUsage === 'object') return message.tokenUsage as TokenUsageItem;
  try {
    const parsed = JSON.parse(message.tokenUsage);
    return typeof parsed === 'object' && parsed !== null ? (parsed as TokenUsageItem) : null;
  } catch {
    return null;
  }
}

export interface ToolTracePayload {
  toolName?: string;
  success?: boolean;
  resultCount?: number;
  durationMs?: number;
  [key: string]: unknown;
}

/**
 * Attempt to interpret a TOOL-role persisted message's content as a tool
 * result payload. Returns null when content is not tool JSON.
 */
export function parseToolTrace(message: ChatMessage): ToolTracePayload | null {
  if (!message.content) return null;
  try {
    const parsed = JSON.parse(message.content);
    return typeof parsed === 'object' && parsed !== null ? (parsed as ToolTracePayload) : null;
  } catch {
    return null;
  }
}

export type DisplayItem =
  | { kind: 'user'; message: ChatMessage }
  | {
      kind: 'assistant';
      message: ChatMessage;
      citations: CitationItem[];
      usage: TokenUsageItem | null;
      live?: boolean;
      toolTrace?: import('@/types/chat').ToolTraceItem[];
      error?: { message: string } | null;
    }
  | { kind: 'tool'; message: ChatMessage };

/**
 * Build a linear display timeline from persisted messages, skipping SYSTEM
 * role entries. Tool-result entries are surfaced as their own compact items so
 * the trace stays visible after a reload.
 */
export function buildTimeline(messages: ChatMessage[]): DisplayItem[] {
  const timeline: DisplayItem[] = [];
  for (const message of messages) {
    if (message.role === 'SYSTEM') continue;
    if (message.role === 'USER') {
      timeline.push({ kind: 'user', message });
    } else if (message.role === 'ASSISTANT') {
      timeline.push({
        kind: 'assistant',
        message,
        citations: parseCitationField(message),
        usage: parseUsageField(message),
      });
    } else if (message.role === 'TOOL') {
      timeline.push({ kind: 'tool', message });
    }
  }
  return timeline;
}