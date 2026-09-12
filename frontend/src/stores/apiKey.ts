import { defineStore } from 'pinia';
import { ref } from 'vue';
import * as apiKeyApi from '@/api/apiKey';
import type { ApiKey, CreateApiKeyRequest, CreateApiKeyResult } from '@/types/apiKey';
import type { ApiError } from '@/types/api';

/**
 * API Key domain store.
 *
 * Secret-safety contract enforced here:
 *  - The one-time raw `fullKey` from CREATE is held as a clearly-marked
 *    TRANSIENT value, never persisted (no Pinia persistence / no Web Storage).
 *  - `clearSecret()` is exposed so the one-time dialog and page lifecycle can
 *    drop it on close, workspace change and logout. `reset()` clears everything.
 *  - List/detail responses never carry fullKey, so items only hold prefixes.
 */
export const useApiKeyStore = defineStore('api-key', () => {
  const items = ref<ApiKey[]>([]);
  const listLoading = ref(false);
  const listError = ref<ApiError | null>(null);
  const actionError = ref<ApiError | null>(null);

  const creating = ref(false);
  const revokingId = ref<number | null>(null);

  /** TRANSIENT: the one-time fullKey returned by create. NOT persistent. */
  const transientSecret = ref<CreateApiKeyResult | null>(null);

  let listSequence = 0;

  function clearSecret(): void {
    transientSecret.value = null;
  }

  function reset(): void {
    items.value = [];
    listError.value = null;
    actionError.value = null;
    revokingId.value = null;
    clearSecret();
  }

  async function fetchList(workspaceId: number): Promise<ApiKey[]> {
    const requestId = ++listSequence;
    listLoading.value = true;
    listError.value = null;
    try {
      const result = await apiKeyApi.listApiKeys(workspaceId);
      if (requestId !== listSequence) return result;
      items.value = result;
      return result;
    } catch (error) {
      if (requestId === listSequence) listError.value = error as ApiError;
      throw error;
    } finally {
      if (requestId === listSequence) listLoading.value = false;
    }
  }

  async function create(workspaceId: number, payload: CreateApiKeyRequest): Promise<CreateApiKeyResult> {
    creating.value = true;
    actionError.value = null;
    try {
      const result = await apiKeyApi.createApiKey(workspaceId, payload);
      // Hold the one-time secret only until the dialog/page clears it.
      transientSecret.value = result;
      await fetchList(workspaceId).catch(() => undefined);
      return result;
    } catch (error) {
      actionError.value = error as ApiError;
      throw error;
    } finally {
      creating.value = false;
    }
  }

  async function revoke(workspaceId: number, keyId: number): Promise<boolean> {
    revokingId.value = keyId;
    actionError.value = null;
    try {
      await apiKeyApi.revokeApiKey(workspaceId, keyId);
      const idx = items.value.findIndex((k) => k.id === keyId);
      if (idx !== -1) items.value[idx] = { ...items.value[idx], status: 'REVOKED', effectiveStatus: 'REVOKED' };
      return true;
    } catch (error) {
      actionError.value = error as ApiError;
      throw error;
    } finally {
      revokingId.value = null;
    }
  }

  return {
    items,
    listLoading,
    listError,
    actionError,
    creating,
    revokingId,
    transientSecret,
    clearSecret,
    reset,
    fetchList,
    create,
    revoke,
  };
});