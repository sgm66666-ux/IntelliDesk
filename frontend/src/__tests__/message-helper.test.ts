import { describe, expect, it } from 'vitest';
import {
  buildTimeline,
  parseCitationField,
  parseToolTrace,
  parseUsageField,
} from '@/lib/message';
import type { ChatMessage } from '@/types/conversation';

function msg(partial: Partial<ChatMessage>): ChatMessage {
  return { id: 1, conversationId: 1, role: 'USER', content: '', status: 'SUCCESS', ...partial };
}

describe('message helpers — RAG + Agent rendering inputs', () => {
  it('parses citation jsonb as a string payload', () => {
    const m = msg({
      role: 'ASSISTANT',
      citation:
        '[{"citationId":1,"documentId":2,"documentName":"doc.pdf","chunkId":3,"content":"snippet","score":0.9}]',
    });
    const citations = parseCitationField(m);
    expect(citations).toHaveLength(1);
    expect(citations[0]).toMatchObject({ citationId: 1, documentId: 2, documentName: 'doc.pdf' });
  });

  it('tolerates citation already decoded as an array', () => {
    const m = msg({
      role: 'ASSISTANT',
      citation: [{ citationId: 2, documentId: 9, chunkId: 4, content: 'x' }] as unknown as string,
    });
    expect(parseCitationField(m)).toHaveLength(1);
  });

  it('returns empty citations for null or invalid data', () => {
    expect(parseCitationField(msg({ role: 'ASSISTANT', citation: null }))).toEqual([]);
    expect(parseCitationField(msg({ role: 'ASSISTANT', citation: 'not json' }))).toEqual([]);
  });

  it('parses token usage payloads', () => {
    const m = msg({ role: 'ASSISTANT', tokenUsage: '{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}' });
    expect(parseUsageField(m)?.total_tokens).toBe(15);
    expect(parseUsageField(msg({ role: 'ASSISTANT', tokenUsage: null }))).toBeNull();
  });

  it('builds a linear timeline and surfaces tool results as their own items', () => {
    const messages = [
      msg({ id: 1, role: 'USER', content: 'q1' }),
      msg({ id: 2, role: 'ASSISTANT', content: 'a1', citation: '[]' }),
      msg({ id: 3, role: 'USER', content: 'q2' }),
      msg({
        id: 4,
        role: 'TOOL',
        content: '{"toolName":"knowledge_search","success":true,"resultCount":3}',
      }),
      msg({ id: 5, role: 'ASSISTANT', content: 'a2' }),
    ];
    const timeline = buildTimeline(messages);
    expect(timeline).toHaveLength(5);
    expect(timeline[0].kind).toBe('user');
    expect(timeline[3].kind).toBe('tool');
  });

  it('ignores SYSTEM-role persisted messages', () => {
    const timeline = buildTimeline([msg({ id: 1, role: 'SYSTEM', content: 'sys' })]);
    expect(timeline).toHaveLength(0);
  });

  it('parses tool trace from a TOOL message content', () => {
    const m = msg({ role: 'TOOL', content: '{"toolName":"calculator","success":false,"resultCount":0}' });
    const trace = parseToolTrace(m);
    expect(trace?.toolName).toBe('calculator');
    expect(trace?.success).toBe(false);
    expect(parseToolTrace(msg({ role: 'TOOL', content: 'not json' }))).toBeNull();
  });
});