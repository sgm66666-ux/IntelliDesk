type FrameScheduler = (callback: FrameRequestCallback) => number;
type FrameCanceller = (handle: number) => void;

export interface StreamingTextRenderer {
  /** Queue text received from the SSE stream for incremental presentation. */
  enqueue: (text: string) => void;
  /** Run the callback after every queued character has been presented. */
  finish: (onDrained: () => void) => void;
  /** Present everything already received immediately. */
  flush: () => void;
  /** Drop queued presentation work without changing already rendered text. */
  cancel: () => void;
}

/**
 * Turns arbitrary SSE token chunks into visible, frame-by-frame text updates.
 *
 * Network chunks do not line up with browser paints: several tokens can arrive
 * in one task and otherwise appear all at once. This small presentation queue
 * keeps the real SSE transport while making every answer visibly incremental.
 * The adaptive batch size also prevents long buffered answers from taking an
 * excessive amount of time to catch up.
 */
export function createStreamingTextRenderer(
  onAppend: (text: string) => void,
  schedule: FrameScheduler = requestAnimationFrame,
  cancelFrame: FrameCanceller = cancelAnimationFrame
): StreamingTextRenderer {
  let queued = '';
  let frameHandle: number | null = null;
  let onDrained: (() => void) | null = null;

  const notifyIfDrained = () => {
    if (queued || frameHandle !== null || !onDrained) return;
    const callback = onDrained;
    onDrained = null;
    callback();
  };

  const renderFrame = () => {
    frameHandle = null;
    if (!queued) {
      notifyIfDrained();
      return;
    }

    const characters = Array.from(queued);
    const batchSize = Math.max(1, Math.ceil(characters.length / 24));
    onAppend(characters.slice(0, batchSize).join(''));
    queued = characters.slice(batchSize).join('');

    if (queued) {
      frameHandle = schedule(renderFrame);
    } else {
      notifyIfDrained();
    }
  };

  const ensureFrame = () => {
    if (frameHandle === null && queued) frameHandle = schedule(renderFrame);
  };

  return {
    enqueue(text) {
      if (!text) return;
      queued += text;
      ensureFrame();
    },
    finish(callback) {
      onDrained = callback;
      ensureFrame();
      notifyIfDrained();
    },
    flush() {
      if (frameHandle !== null) {
        cancelFrame(frameHandle);
        frameHandle = null;
      }
      if (queued) {
        onAppend(queued);
        queued = '';
      }
      notifyIfDrained();
    },
    cancel() {
      if (frameHandle !== null) cancelFrame(frameHandle);
      frameHandle = null;
      queued = '';
      onDrained = null;
    },
  };
}
