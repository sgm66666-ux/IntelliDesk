import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import * as authApi from '@/api/auth';
import type { LoginRequest, RegisterRequest } from '@/types/api';

let accessToken: string | null = null;

// Expose for Axios interceptor
(window as any).__INJECT_ACCESS_TOKEN__ = () => accessToken;
(window as any).__SET_ACCESS_TOKEN__ = (token: string) => {
  accessToken = token;
};
(window as any).__CLEAR_AUTH__ = () => {
  accessToken = null;
};

export const useAuthStore = defineStore('auth', () => {
  const user = ref<{ userId: number; username: string } | null>(null);
  const loading = ref(false);
  const bootstrapReady = ref(false);

  const authenticated = computed(() => user.value !== null && accessToken !== null);

  async function loginAction(data: LoginRequest) {
    loading.value = true;
    try {
      const result = await authApi.login(data);
      accessToken = result.accessToken;
      user.value = { userId: result.userId, username: result.username };
      // refreshToken is ignored by frontend - handled via HttpOnly cookie
    } finally {
      loading.value = false;
    }
  }

  async function registerAction(data: RegisterRequest) {
    loading.value = true;
    try {
      const result = await authApi.register(data);
      accessToken = result.accessToken;
      user.value = { userId: result.userId, username: result.username };
    } finally {
      loading.value = false;
    }
  }

  async function bootstrapAuth() {
    if (bootstrapReady.value) return;
    try {
      const result = await authApi.refresh();
      accessToken = result.accessToken;
      user.value = { userId: result.userId, username: result.username };
    } catch {
      accessToken = null;
      user.value = null;
    } finally {
      bootstrapReady.value = true;
    }
  }

  async function logoutAction() {
    try {
      await authApi.logout();
    } finally {
      accessToken = null;
      user.value = null;
    }
  }

  return {
    user,
    loading,
    bootstrapReady,
    authenticated,
    loginAction,
    registerAction,
    bootstrapAuth,
    logoutAction,
  };
});