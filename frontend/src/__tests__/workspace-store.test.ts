import { describe, it, expect, beforeEach, vi } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { useWorkspaceStore } from '@/stores/workspace';

vi.mock('@/api/workspace', () => ({
  listWorkspaces: vi.fn(),
  getWorkspace: vi.fn(),
  createWorkspace: vi.fn(),
  updateWorkspace: vi.fn(),
  deleteWorkspace: vi.fn(),
}));

import * as workspaceApi from '@/api/workspace';

describe('Workspace Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  describe('fetchList', () => {
    it('loads workspaces', async () => {
      const mockWorkspaces = [
        { id: 1, name: 'WS1', description: 'Desc1', ownerId: 1, createdAt: '', updatedAt: '' },
        { id: 2, name: 'WS2', description: 'Desc2', ownerId: 1, createdAt: '', updatedAt: '' },
      ];
      vi.mocked(workspaceApi.listWorkspaces).mockResolvedValue(mockWorkspaces);

      const store = useWorkspaceStore();
      await store.fetchList();

      expect(store.workspaces).toHaveLength(2);
      expect(store.workspaces[0].name).toBe('WS1');
    });

    it('sets error on API failure', async () => {
      vi.mocked(workspaceApi.listWorkspaces).mockRejectedValue(new Error('Network error'));

      const store = useWorkspaceStore();
      await store.fetchList();

      expect(store.error).toBeTruthy();
    });
  });

  describe('create', () => {
    it('adds workspace to list', async () => {
      const newWs = { id: 3, name: 'New WS', description: '', ownerId: 1, createdAt: '', updatedAt: '' };
      vi.mocked(workspaceApi.createWorkspace).mockResolvedValue(newWs);

      const store = useWorkspaceStore();
      const result = await store.create({ name: 'New WS' });

      expect(result.name).toBe('New WS');
      expect(store.workspaces).toHaveLength(1);
    });
  });

  describe('remove', () => {
    it('removes workspace from list', async () => {
      vi.mocked(workspaceApi.deleteWorkspace).mockResolvedValue(undefined);
      const store = useWorkspaceStore();
      store.$patch({
        workspaces: [
          { id: 1, name: 'WS1', description: '', ownerId: 1, createdAt: '', updatedAt: '' },
          { id: 2, name: 'WS2', description: '', ownerId: 1, createdAt: '', updatedAt: '' },
        ],
      });

      await store.remove(1);

      expect(store.workspaces).toHaveLength(1);
      expect(store.workspaces[0].id).toBe(2);
    });
  });
});