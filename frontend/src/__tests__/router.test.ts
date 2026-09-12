import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { useAuthStore } from '@/stores/auth';
import { createMemoryHistory } from 'vue-router';
import { createAppRouter } from '@/router';

const authApiMocks = vi.hoisted(() => ({
  login: vi.fn(),
  register: vi.fn(),
  refresh: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('@/api/auth', () => authApiMocks);

describe('Router guards', () => {
  let router: ReturnType<typeof createAppRouter>;

  beforeEach(() => {
    setActivePinia(createPinia());
    vi.resetAllMocks();
    (window as any).__CLEAR_AUTH__?.();
    router = createAppRouter(createMemoryHistory());
  });

  afterEach(() => {
    (window as any).__CLEAR_AUTH__?.();
  });

  it('redirects unauthenticated user to /login', async () => {
    const auth = useAuthStore();
    auth.$patch({ bootstrapReady: true, user: null });

    await router.push('/workspaces');
    expect(router.currentRoute.value.path).toBe('/login');
  });

  it('allows authenticated user to access /workspaces', async () => {
    const auth = useAuthStore();
    auth.$patch({ bootstrapReady: true, user: { userId: 1, username: 'test' } });
    (window as any).__SET_ACCESS_TOKEN__?.('at-test');

    await router.push('/workspaces');
    expect(router.currentRoute.value.path).toBe('/workspaces');
  });

  it('redirects authenticated user from /login to /workspaces', async () => {
    const auth = useAuthStore();
    auth.$patch({ bootstrapReady: true, user: { userId: 1, username: 'test' } });
    (window as any).__SET_ACCESS_TOKEN__?.('at-test');

    await router.push('/login');
    expect(router.currentRoute.value.path).toBe('/workspaces');
  });

  it('awaits auth bootstrap before evaluating a protected route', async () => {
    authApiMocks.refresh.mockResolvedValue({
      accessToken: 'bootstrapped-token',
      userId: 7,
      username: 'bootstrapped',
    });

    await router.push('/workspaces');

    expect(authApiMocks.refresh).toHaveBeenCalledOnce();
    expect(router.currentRoute.value.path).toBe('/workspaces');
  });
});
