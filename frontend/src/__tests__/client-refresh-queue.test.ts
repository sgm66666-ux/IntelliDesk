import { describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  requestUse: vi.fn(),
  responseUse: vi.fn(),
  client: vi.fn(),
  refreshRequest: vi.fn(),
}));

vi.mock('axios', () => ({
  default: {
    create: vi.fn(() => {
      (mocks.client as any).interceptors = {
        request: { use: mocks.requestUse },
        response: { use: mocks.responseUse },
      };
      return mocks.client;
    }),
    post: mocks.refreshRequest,
  },
}));

describe('refresh failure queue', () => {
  it('rejects all concurrent 401 requests after one failed refresh', async () => {
    vi.resetModules();
    mocks.refreshRequest.mockRejectedValue(new Error('refresh failed'));
    const clearAuth = vi.fn();
    (window as any).__CLEAR_AUTH__ = clearAuth;

    await import('@/api/client');
    const onResponseError = mocks.responseUse.mock.calls[0][1] as (error: unknown) => Promise<unknown>;
    const make401 = (id: string) => ({
      config: { url: `/api/workspaces/${id}`, headers: {} },
      response: { status: 401, data: { code: 401, message: 'unauthorized' } },
      message: 'unauthorized',
    });

    const results = await Promise.allSettled([
      onResponseError(make401('one')),
      onResponseError(make401('two')),
      onResponseError(make401('three')),
    ]);

    expect(mocks.refreshRequest).toHaveBeenCalledTimes(1);
    expect(results.every((result) => result.status === 'rejected')).toBe(true);
    expect(clearAuth).toHaveBeenCalledTimes(1);
    expect(mocks.client).not.toHaveBeenCalled();

    delete (window as any).__CLEAR_AUTH__;
  });
});
