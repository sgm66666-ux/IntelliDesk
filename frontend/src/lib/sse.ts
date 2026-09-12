import type { ParsedSseEvent } from '@/types/chat';
import { ApiError } from '@/types/api';

/**
 * Buffered SSE parser.
 *
 * A single TCP/fetch chunk may contain a partial event, a complete event, or
 * several complete events. This parser accumulates a buffer and only emits a
 * full SSE dispatch block once its trailing blank-line delimiter is seen.
 *
 * Accepted framing:
 *   event:<name>
 *   data:<payload>            (multi-line data joined with "\n")
 *   id:<id>                   (optional)
 *   <blank line>              -> event delimiter
 *
 * \r\n is normalized to \n before parsing.
 */
export class SseParser {
  private buffer = '';

  /**
   * Feed raw text from the stream; returns any newly completed events.
   * Incomplete trailing data is held in the internal buffer for the next call.
   */
  parse(chunk: string): ParsedSseEvent[] {
    const normalized = chunk.replace(/\r\n/g, '\n');
    this.buffer += normalized;
    return this.consume();
  }

  /**
   * Drain remaining buffered data. If there is a partial event with no blank
   * line, flush its accumulated fields so nothing is silently dropped on EOF.
   */
  flush(): ParsedSseEvent[] {
    const events = this.consume();
    if (this.buffer.length > 0) {
      const parsed = this.parseEventBlock(this.buffer);
      this.buffer = '';
      if (parsed) events.push(parsed);
    }
    return events;
  }

  private consume(): ParsedSseEvent[] {
    const events: ParsedSseEvent[] = [];
    let idx: number;
    while ((idx = this.buffer.indexOf('\n\n')) !== -1) {
      const block = this.buffer.slice(0, idx);
      this.buffer = this.buffer.slice(idx + 2);
      const parsed = this.parseEventBlock(block);
      if (parsed) events.push(parsed);
    }
    return events;
  }

  private parseEventBlock(block: string): ParsedSseEvent | null {
    const lines = block.split('\n');
    let event = '';
    let id = '';
    const dataLines: string[] = [];
    for (const rawLine of lines) {
      const line = rawLine.startsWith(':') ? '' : rawLine;
      if (!line) continue;
      if (line.startsWith('event:')) {
        event = line.slice('event:'.length).trim();
      } else if (line.startsWith('id:')) {
        id = line.slice('id:'.length).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice('data:'.length).trimStart());
      }
    }
    if (dataLines.length === 0) return null;
    return { event: event || 'message', data: dataLines.join('\n'), id: id || undefined };
  }
}

export interface SseClientOptions {
  url: string;
  method?: 'GET' | 'POST';
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  onEvent: (event: ParsedSseEvent) => void;
  /** The response reader reached EOF (stream closed by server). */
  onStreamEnded?: () => void;
  /** Non-2xx response, bad content-type, or read failure. */
  onHttpError?: (error: Error) => void;
}

export interface SseClientHandle {
  abort: () => void;
}

/**
 * POST (or GET) SSE client built on fetch + ReadableStream.
 *
 * Never auto-resubmits: at most one request is issued. If the connection is
 * lost the caller is notified via onStreamEnded/onHttpError and must decide
 * how to recover (e.g. reload persisted messages) — we never POST again.
 */
export function connectSse(options: SseClientOptions): SseClientHandle {
  const { url, method = 'POST', body, headers = {}, signal } = options;
  const controller = new AbortController();
  const aborted = { value: false };

  const abortAll = () => {
    aborted.value = true;
    controller.abort();
  };

  (async () => {
    // Combine external signal with our own so route change / stop can abort.
    let externalHandle: (() => void) | undefined;
    if (signal) {
      if (signal.aborted) {
        abortAll();
        return;
      }
      const onAbort = () => abortAll();
      signal.addEventListener('abort', onAbort);
      externalHandle = () => signal.removeEventListener('abort', onAbort);
    }

    try {
      const init: RequestInit = {
        method,
        headers,
        signal: controller.signal,
      };
      if (body !== undefined) init.body = JSON.stringify(body);

      const response = await fetch(url, init);

      if (!response.ok) {
        let message = `HTTP ${response.status}`;
        try {
          const text = await response.text();
          if (text) {
            // backend envelope is { code, message, traceId }
            const parsed = JSON.parse(text);
            if (parsed && typeof parsed.message === 'string') {
              message = parsed.message;
            }
          }
        } catch {
          /* non-JSON body — keep default message */
        }
        options.onHttpError?.(
          new ApiError(Number(response.status), message, '', response.status)
        );
        return;
      }

      const contentType = response.headers.get('content-type') || '';
      if (contentType && !contentType.includes('text/event-stream')) {
        options.onHttpError?.(new Error('Unexpected content-type: ' + contentType));
        return;
      }

      const reader = response.body?.getReader();
      if (!reader) {
        options.onHttpError?.(new Error('Response body is not readable'));
        return;
      }

      const decoder = new TextDecoder('utf-8');
      const parser = new SseParser();

      // Loop reading chunks — however they arrive relative to event boundaries.
      // eslint-disable-next-line no-constant-condition
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        const text = decoder.decode(value, { stream: true });
        if (!text) continue;
        const events = parser.parse(text);
        for (const ev of events) {
          if (aborted.value) return;
          options.onEvent(ev);
        }
      }

      // EOF. Drain any trailing partial event.
      const trailing = parser.flush();
      for (const ev of trailing) {
        if (aborted.value) return;
        options.onEvent(ev);
      }
      options.onStreamEnded?.();
    } catch (err) {
      if (aborted.value) return; // user abort — not a failure
      options.onHttpError?.(
        err instanceof Error ? err : new Error('Stream read failed')
      );
    } finally {
      externalHandle?.();
    }
  })();

  return { abort: abortAll };
}