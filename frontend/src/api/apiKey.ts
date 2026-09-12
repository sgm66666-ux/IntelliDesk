import client from './client';
import type { ApiKey, CreateApiKeyRequest, CreateApiKeyResult } from '@/types/apiKey';

const basePath = (workspaceId: number) => `/workspaces/${workspaceId}/api-keys`;

export function listApiKeys(workspaceId: number): Promise<ApiKey[]> {
  return client.get(basePath(workspaceId));
}

export function getApiKey(workspaceId: number, keyId: number): Promise<ApiKey> {
  return client.get(`${basePath(workspaceId)}/${keyId}`);
}

export function createApiKey(
  workspaceId: number,
  data: CreateApiKeyRequest
): Promise<CreateApiKeyResult> {
  return client.post(basePath(workspaceId), data);
}

export function revokeApiKey(workspaceId: number, keyId: number): Promise<void> {
  return client.delete(`${basePath(workspaceId)}/${keyId}`);
}