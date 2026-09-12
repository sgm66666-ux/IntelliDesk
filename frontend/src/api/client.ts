import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import type { ApiResult } from '@/types/api';
import { ApiError } from '@/types/api';

const client = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
});

let isRefreshing = false;
type RefreshSubscriber = {
  resolve: (token: string) => void;
  reject: (error: unknown) => void;
};
let refreshSubscribers: RefreshSubscriber[] = [];

function subscribeTokenRefresh(resolve: (token: string) => void, reject: (error: unknown) => void) {
  refreshSubscribers.push({ resolve, reject });
}

function onTokenRefreshed(newToken: string) {
  const subscribers = refreshSubscribers;
  refreshSubscribers = [];
  subscribers.forEach(({ resolve }) => resolve(newToken));
}

function onTokenRefreshFailed(error: unknown) {
  const subscribers = refreshSubscribers;
  refreshSubscribers = [];
  subscribers.forEach(({ reject }) => reject(error));
}

// Request interceptor: attach access token
client.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = (window as any).__INJECT_ACCESS_TOKEN__?.();
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Response interceptor: unwrap ApiResult, handle 401 refresh
client.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult;
    if (body.code === 0) {
      return body.data as any;
    }
    throw new ApiError(body.code, body.message, body.traceId, response.status);
  },
  async (error: AxiosError) => {
    const originalRequest = error.config as InternalAxiosRequestConfig & { _retry?: boolean };
    const status = error.response?.status;

    // Skip refresh for auth endpoints
    const skipPaths = ['/api/auth/login', '/api/auth/register', '/api/auth/refresh'];
    const isAuthPath = skipPaths.some((p) => originalRequest?.url?.includes(p));

    if (status === 401 && !isAuthPath && !originalRequest?._retry) {
      if (isRefreshing) {
        return new Promise<string>((resolve, reject) => {
          subscribeTokenRefresh(resolve, reject);
        }).then((newToken) => {
          if (originalRequest.headers) {
            originalRequest.headers.Authorization = `Bearer ${newToken}`;
          }
          return client(originalRequest);
        });
      }

      isRefreshing = true;
      originalRequest._retry = true;

      try {
        const refreshResult = await axios.post<ApiResult<{ accessToken: string }>>(
          '/api/auth/refresh',
          {},
          { withCredentials: true }
        );
        const newToken = refreshResult.data.data.accessToken;
        (window as any).__SET_ACCESS_TOKEN__?.(newToken);
        onTokenRefreshed(newToken);
        if (originalRequest.headers) {
          originalRequest.headers.Authorization = `Bearer ${newToken}`;
        }
        return client(originalRequest);
      } catch (refreshError) {
        onTokenRefreshFailed(refreshError);
        (window as any).__CLEAR_AUTH__?.();
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
    }

    // Map non-401 errors to ApiError
    if (error.response?.data) {
      const body = error.response.data as ApiResult;
      throw new ApiError(body.code || status || 0, body.message || error.message, body.traceId || '', status);
    }
    throw new ApiError(status || 0, error.message, '', status);
  }
);

export default client;