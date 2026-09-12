import client from './client';
import type { LoginRequest, RegisterRequest, LoginResponse } from '@/types/api';

export function login(data: LoginRequest): Promise<LoginResponse> {
  return client.post('/auth/login', data);
}

export function register(data: RegisterRequest): Promise<LoginResponse> {
  return client.post('/auth/register', data);
}

export function refresh(): Promise<LoginResponse> {
  return client.post('/auth/refresh', {});
}

export function logout(): Promise<void> {
  return client.post('/auth/logout', {});
}