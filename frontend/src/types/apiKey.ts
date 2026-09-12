/**
 * API Key domain types.
 *
 * These mirror the Phase 6 backend contract exactly:
 *  - List/detail responses NEVER include fullKey.
 *  - Only the CREATE response includes the one-time fullKey.
 *  - Status comes from backend (ACTIVE / EXPIRED / REVOKED via effectiveStatus).
 */
export type ApiKeyScope = 'READ';

export type ApiKeyStatus = 'ACTIVE' | 'REVOKED';
export type ApiKeyEffectiveStatus = 'ACTIVE' | 'EXPIRED' | 'REVOKED';

export interface ApiKey {
  id: number;
  workspaceId: number;
  name: string;
  keyPrefix: string;
  scope: ApiKeyScope;
  status: ApiKeyStatus;
  effectiveStatus: ApiKeyEffectiveStatus;
  expiresAt?: string;
  lastUsedAt?: string;
  createdAt: string;
  revokedAt?: string;
}

export interface CreateApiKeyRequest {
  name: string;
  scope?: ApiKeyScope;
  expiresAt?: string;
}

/**
 * 创建成功返回的唯一一次 fullKey（plaintext one-time）。
 * 只允许存在 create-success 的短生命周期 runtime state。
 */
export interface CreateApiKeyResult {
  id: number;
  workspaceId: number;
  name: string;
  keyPrefix: string;
  scope: ApiKeyScope;
  fullKey: string;
  expiresAt?: string;
  createdAt: string;
}