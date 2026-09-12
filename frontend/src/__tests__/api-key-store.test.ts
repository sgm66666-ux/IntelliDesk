import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  listApiKeys: vi.fn(),
  getApiKey: vi.fn(),
  createApiKey: vi.fn(),
  revokeApiKey: vi.fn(),
}));

vi.mock('@/api/apiKey', () => mocks);

import { setActivePinia, createPinia } from 'pinia';
import { useApiKeyStore } from '@/stores/apiKey';
import { ApiError } from '@/types/api';
import type { ApiKey } from '@/types/apiKey';

const keyA = {
  id: 1, workspaceId: 7, name: 'A', keyPrefix: 'sk-aa', scope: 'READ',
  status: 'ACTIVE', effectiveStatus: 'ACTIVE', createdAt: '1',
};
const keyB = {
  id: 2, workspaceId: 7, name: 'B', keyPrefix: 'sk-bb', scope: 'READ',
  status: 'ACTIVE', effectiveStatus: 'ACTIVE', createdAt: '2',
};
const createdResult = {
  id: 3, workspaceId: 7, name: 'C', keyPrefix: 'sk-cc', scope: 'READ',
  fullKey: 'sk-cc-secret-secret', createdAt: '3',
};

describe('api key store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it('loads the list and holds it in state', async () => {
    mocks.listApiKeys.mockResolvedValue([keyA, keyB]);
    const store = useApiKeyStore();
    await store.fetchList(7);
    expect(store.items.map((k) => k.id)).toEqual([1, 2]);
    expect(store.listLoading).toBe(false);
    expect(store.listError).toBeNull();
  });

  it('normalizes list errors to ApiError surface fields', async () => {
    mocks.listApiKeys.mockRejectedValue(new ApiError(403, 'forbidden', 'tr', 403));
    const store = useApiKeyStore();
    await expect(store.fetchList(7)).rejects.toBeInstanceOf(ApiError);
    expect(store.listError as ApiError).toMatchObject({ code: 403, message: 'forbidden', traceId: 'tr' });
  });

  it('creates a key, stores the one-time secret transiently, and refreshes the list', async () => {
    mocks.createApiKey.mockResolvedValue(createdResult);
    mocks.listApiKeys.mockResolvedValue([keyA, keyB, createdResult]);
    const store = useApiKeyStore();
    const result = await store.create(7, { name: 'C', scope: 'READ' });
    expect(result.fullKey).toBe('sk-cc-secret-secret');
    expect(store.transientSecret?.fullKey).toBe('sk-cc-secret-secret');
    expect(mocks.listApiKeys).toHaveBeenCalledOnce();
    expect(store.items).toHaveLength(3);
  });

  it('does NOT put fullKey into the list items (no secret persistence in list)', async () => {
    // The list backend response is ApiKey[] and never includes fullKey.
    const listed: ApiKey = {
      id: createdResult.id, workspaceId: createdResult.workspaceId, name: createdResult.name,
      keyPrefix: createdResult.keyPrefix, scope: 'READ', status: 'ACTIVE', effectiveStatus: 'ACTIVE',
      createdAt: createdResult.createdAt,
    };
    mocks.createApiKey.mockResolvedValue(createdResult);
    mocks.listApiKeys.mockResolvedValue([listed]);
    const store = useApiKeyStore();
    await store.create(7, { name: 'C' });
    // transient secret exists from the create response...
    expect(store.transientSecret?.fullKey).toBe('sk-cc-secret-secret');
    // ...but the refreshed list items only ever hold the prefix, never the secret.
    expect(store.items[0]).not.toHaveProperty('fullKey');
    expect(store.items[0].keyPrefix).toBe('sk-cc');
  });

  it('clearSecret drops the transient one-time secret', async () => {
    mocks.createApiKey.mockResolvedValue(createdResult);
    mocks.listApiKeys.mockResolvedValue([]);
    const store = useApiKeyStore();
    await store.create(7, { name: 'C' });
    expect(store.transientSecret).not.toBeNull();
    store.clearSecret();
    expect(store.transientSecret).toBeNull();
  });

  it('workspace switch reset clears list, error and transient secret', async () => {
    mocks.listApiKeys.mockResolvedValue([keyA]);
    const store = useApiKeyStore();
    await store.fetchList(7);
    expect(store.items).toHaveLength(1);
    store.reset();
    expect(store.items).toHaveLength(0);
    expect(store.transientSecret).toBeNull();
    expect(store.listError).toBeNull();
  });

  it('ignores a stale list response after a newer fetch started (A late does not overwrite B)', async () => {
    let resolveA!: (v: unknown) => void;
    mocks.listApiKeys.mockImplementationOnce(() => new Promise((r) => (resolveA = r)));
    mocks.listApiKeys.mockResolvedValue([keyB]);
    const store = useApiKeyStore();
    const pA = store.fetchList(1);
    const pB = store.fetchList(9);
    resolveA([keyA]);
    await Promise.all([pA, pB]);
    expect(store.items).toEqual([keyB]);
  });

  it('revoke updates the item to REVOKED in place', async () => {
    mocks.listApiKeys.mockResolvedValue([keyA]);
    mocks.revokeApiKey.mockResolvedValue(undefined);
    const store = useApiKeyStore();
    await store.fetchList(7);
    await store.revoke(7, 1);
    expect(store.items[0].status).toBe('REVOKED');
    expect(store.items[0].effectiveStatus).toBe('REVOKED');
    expect(store.revokingId).toBeNull();
  });

  it('normalizes revoke failure into ApiError and keeps the key ACTIVE', async () => {
    mocks.listApiKeys.mockResolvedValue([keyA]);
    mocks.revokeApiKey.mockRejectedValue(new ApiError(404, 'not found', 'tr', 404));
    const store = useApiKeyStore();
    await store.fetchList(7);
    await expect(store.revoke(7, 1)).rejects.toBeInstanceOf(ApiError);
    expect(store.items[0].status).toBe('ACTIVE');
    expect(store.revokingId).toBeNull();
  });
});