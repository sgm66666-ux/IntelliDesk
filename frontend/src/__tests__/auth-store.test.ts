import { describe, it, expect, beforeEach, vi } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { useAuthStore } from '@/stores/auth';

// Mock the auth API
vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  register: vi.fn(),
  refresh: vi.fn(),
  logout: vi.fn(),
}));

import * as authApi from '@/api/auth';

describe('Auth Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    // Reset access token
    (window as any).__CLEAR_AUTH__?.();
  });

  describe('login', () => {
    it('sets user and accessToken on success', async () => {
      const mockResponse = { accessToken: 'at-1', refreshToken: 'rt-1', userId: 1, username: 'test' };
      vi.mocked(authApi.login).mockResolvedValue(mockResponse);

      const store = useAuthStore();
      await store.loginAction({ username: 'test', password: 'pass' });

      expect(store.authenticated).toBe(true);
      expect(store.user?.username).toBe('test');
      expect((window as any).__INJECT_ACCESS_TOKEN__()).toBe('at-1');
    });

    it('refreshToken is NOT stored in Pinia', async () => {
      const mockResponse = { accessToken: 'at-1', refreshToken: 'rt-1', userId: 1, username: 'test' };
      vi.mocked(authApi.login).mockResolvedValue(mockResponse);

      const store = useAuthStore();
      await store.loginAction({ username: 'test', password: 'pass' });

      // refreshToken should NOT be in store state
      const state = store.$state as any;
      expect(state.refreshToken).toBeUndefined();
    });
  });

  describe('register', () => {
    it('sets user and accessToken on success', async () => {
      const mockResponse = { accessToken: 'at-2', refreshToken: 'rt-2', userId: 2, username: 'newuser' };
      vi.mocked(authApi.register).mockResolvedValue(mockResponse);

      const store = useAuthStore();
      await store.registerAction({ username: 'newuser', password: 'pass' });

      expect(store.authenticated).toBe(true);
      expect(store.user?.username).toBe('newuser');
    });
  });

  describe('bootstrapAuth', () => {
    it('restores session on successful refresh', async () => {
      const mockResponse = { accessToken: 'at-3', refreshToken: 'rt-3', userId: 3, username: 'restored' };
      vi.mocked(authApi.refresh).mockResolvedValue(mockResponse);

      const store = useAuthStore();
      await store.bootstrapAuth();

      expect(store.authenticated).toBe(true);
      expect(store.user?.username).toBe('restored');
      expect(store.bootstrapReady).toBe(true);
    });

    it('clears state on refresh failure', async () => {
      vi.mocked(authApi.refresh).mockRejectedValue(new Error('Refresh failed'));

      const store = useAuthStore();
      await store.bootstrapAuth();

      expect(store.authenticated).toBe(false);
      expect(store.user).toBeNull();
      expect(store.bootstrapReady).toBe(true);
    });
  });

  describe('logout', () => {
    it('clears user and accessToken', async () => {
      vi.mocked(authApi.logout).mockResolvedValue(undefined);
      // Set initial state
      const store = useAuthStore();
      (window as any).__SET_ACCESS_TOKEN__?.('at-logout');
      store.$patch({ user: { userId: 1, username: 'test' } });

      await store.logoutAction();

      expect(store.authenticated).toBe(false);
      expect(store.user).toBeNull();
      expect((window as any).__INJECT_ACCESS_TOKEN__()).toBeNull();
    });

    it('clears state even if logout API fails', async () => {
      vi.mocked(authApi.logout).mockRejectedValue(new Error('Network error'));
      const store = useAuthStore();
      (window as any).__SET_ACCESS_TOKEN__?.('at-fail');
      store.$patch({ user: { userId: 1, username: 'test' } });

      // logoutAction does not catch errors, so the rejection propagates
      await expect(store.logoutAction()).rejects.toThrow('Network error');

      expect(store.authenticated).toBe(false);
      expect(store.user).toBeNull();
    });
  });

  describe('accessToken memory only', () => {
    it('accessToken is stored in memory, not localStorage', () => {
      (window as any).__SET_ACCESS_TOKEN__?.('at-memory');
      expect(localStorage.getItem('accessToken')).toBeNull();
      expect(sessionStorage.getItem('accessToken')).toBeNull();
    });
  });
});