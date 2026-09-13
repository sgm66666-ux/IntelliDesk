import { describe, expect, it, vi } from 'vitest';
import { createStreamingTextRenderer } from '@/lib/streamingText';

function frameHarness() {
  let nextId = 0;
  const callbacks = new Map<number, FrameRequestCallback>();
  const schedule = vi.fn((callback: FrameRequestCallback) => {
    const id = ++nextId;
    callbacks.set(id, callback);
    return id;
  });
  const cancel = vi.fn((id: number) => callbacks.delete(id));
  const runNext = () => {
    const next = callbacks.entries().next().value as [number, FrameRequestCallback] | undefined;
    if (!next) return false;
    callbacks.delete(next[0]);
    next[1](performance.now());
    return true;
  };
  const runAll = () => {
    while (runNext()) {
      // Drain every frame scheduled by the renderer.
    }
  };
  return { schedule, cancel, runNext, runAll };
}

describe('streaming text renderer', () => {
  it('presents received text over multiple animation frames without loss', () => {
    const frames = frameHarness();
    const parts: string[] = [];
    const renderer = createStreamingTextRenderer(
      (part) => parts.push(part),
      frames.schedule,
      frames.cancel
    );

    renderer.enqueue('IntelliDesk 正在流式回答这个问题。');

    expect(parts).toEqual([]);
    expect(frames.runNext()).toBe(true);
    expect(parts.join('').length).toBeGreaterThan(0);
    expect(parts.join('')).not.toBe('IntelliDesk 正在流式回答这个问题。');

    frames.runAll();
    expect(parts.join('')).toBe('IntelliDesk 正在流式回答这个问题。');
    expect(parts.length).toBeGreaterThan(1);
  });

  it('waits for queued text before completing the live answer', () => {
    const frames = frameHarness();
    let output = '';
    const completed = vi.fn();
    const renderer = createStreamingTextRenderer(
      (part) => { output += part; },
      frames.schedule,
      frames.cancel
    );

    renderer.enqueue('完整答案');
    renderer.finish(completed);
    expect(completed).not.toHaveBeenCalled();

    frames.runAll();
    expect(output).toBe('完整答案');
    expect(completed).toHaveBeenCalledOnce();
  });

  it('flushes received text immediately and cancels queued rendering safely', () => {
    const frames = frameHarness();
    let output = '';
    const renderer = createStreamingTextRenderer(
      (part) => { output += part; },
      frames.schedule,
      frames.cancel
    );

    renderer.enqueue('部分内容');
    renderer.flush();
    expect(output).toBe('部分内容');
    expect(frames.cancel).toHaveBeenCalledOnce();

    renderer.enqueue('不会显示');
    renderer.cancel();
    frames.runAll();
    expect(output).toBe('部分内容');
  });
});
