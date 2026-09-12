import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { SseParser } from '@/lib/sse';

function toEvent(ev: { event: string; data: string; id?: string }) {
  return { event: ev.event, data: ev.data, id: ev.id };
}

describe('SseParser — buffered framing', () => {
  it('reassembles an event split across multiple chunks', () => {
    const parser = new SseParser();
    // chunk 1: "event: tok"  chunk 2: "en\ndata: hel"  chunk 3: "lo\n\n"
    expect(parser.parse('event: tok')).toEqual([]);
    expect(parser.parse('en\ndata: hel')).toEqual([]);
    const out = parser.parse('lo\n\n');
    expect(out).toHaveLength(1);
    expect(toEvent(out[0])).toEqual({ event: 'token', data: 'hello', id: undefined });
  });

  it('splits multiple events delivered in a single chunk', () => {
    const parser = new SseParser();
    const out = parser.parse(
      'event:token\ndata: hi\n\nevent:done\ndata: {"finishReason":"stop"}\n\n'
    );
    expect(out).toHaveLength(2);
    expect(toEvent(out[0])).toEqual({ event: 'token', data: 'hi', id: undefined });
    expect(toEvent(out[1].event === 'done' ? out[1] : out[1])).toEqual({
      event: 'done',
      data: '{"finishReason":"stop"}',
      id: undefined,
    });
  });

  it('emits a done event as an ordinary event and stops cleanly', () => {
    const parser = new SseParser();
    const out = parser.parse('event:done\ndata: {"messageId":7}\n\n');
    expect(out).toHaveLength(1);
    expect(out[0].event).toBe('done');
  });

  it('does not loop forever on malformed / unterminated input', () => {
    const parser = new SseParser();
    const out = parser.parse('event:token\ndata: {bad json');
    expect(out).toEqual([]); // held in buffer, not emitted
    // feeding garbage repeatedly just keeps growing the buffer, never emits a bad event
    const more = parser.parse('garbage without delimiter');
    expect(more).toEqual([]);
    // flush drains it as a single data-bearing event (parse won't crash)
    const flushed = parser.flush();
    expect(flushed).toHaveLength(1);
    expect(flushed[0].data).toBe('{bad jsongarbage without delimiter');
  });

  it('treats CRLF framing the same as LF', () => {
    const parser = new SseParser();
    const out = parser.parse('event:token\r\ndata: x\r\n\r\n');
    expect(out).toHaveLength(1);
    expect(out[0].event).toBe('token');
    expect(out[0].data).toBe('x');
  });

  it('joins multiple data lines into one payload', () => {
    const parser = new SseParser();
    const out = parser.parse('event:token\ndata: part1\ndata: part2\n\n');
    expect(out).toHaveLength(1);
    expect(out[0].data).toBe('part1\npart2');
  });
});

describe('SseParser — flush on EOF', () => {
  it('flushes a partial trailing event when the stream ends', () => {
    const parser = new SseParser();
    parser.parse('event:token\ndata: tail');
    const flushed = parser.flush();
    expect(flushed).toHaveLength(1);
    expect(toEvent(flushed[0])).toEqual({ event: 'token', data: 'tail', id: undefined });
  });
});