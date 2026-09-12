import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  listConversations: vi.fn(),
  getConversation: vi.fn(),
  createConversation: vi.fn(),
  updateConversationTitle: vi.fn(),
  deleteConversation: vi.fn(),
  getConversationMessages: vi.fn(),
}));

vi.mock('@/api/conversation', () => mocks);

import { setActivePinia, createPinia } from 'pinia';
import { useConversationStore } from '@/stores/conversation';
import { ApiError } from '@/types/api';

const convA = { id: 1, workspaceId: 7, userId: 1, title: 'A', status: 'ACTIVE', createdAt: '1', updatedAt: '1' };
const convB = { id: 2, workspaceId: 7, userId: 1, title: 'B', status: 'ACTIVE', createdAt: '1', updatedAt: '1' };

describe('conversation store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it('loads the conversation list and holds it in state', async () => {
    mocks.listConversations.mockResolvedValue([convA, convB]);
    const store = useConversationStore();
    await store.fetchList(7);
    expect(store.items.map((c) => c.id)).toEqual([1, 2]);
    expect(store.listLoading).toBe(false);
    expect(store.listError).toBeNull();
  });

  it('normalizes list errors to ApiError surface fields', async () => {
    mocks.listConversations.mockRejectedValue(new ApiError(3001, 'not found', 'tr', 404));
    const store = useConversationStore();
    await expect(store.fetchList(7)).rejects.toBeInstanceOf(ApiError);
    expect(store.listError as ApiError).toMatchObject({ code: 3001, message: 'not found', traceId: 'tr' });
  });

  it('loads messages for the current conversation', async () => {
    mocks.getConversationMessages.mockResolvedValue([
      { id: 11, conversationId: 1, role: 'USER', content: 'q', status: 'SUCCESS' },
    ]);
    const store = useConversationStore();
    await store.fetchMessages(7, 1);
    expect(store.messages).toHaveLength(1);
    expect(store.messages[0].role).toBe('USER');
  });

  it('creates a conversation, refreshes the list, and selects it', async () => {
    mocks.createConversation.mockResolvedValue(convA);
    mocks.listConversations.mockResolvedValue([convA]);
    const store = useConversationStore();
    const created = await store.create(7, { title: '' });
    expect(created.id).toBe(1);
    expect(store.current?.id).toBe(1);
    expect(mocks.listConversations).toHaveBeenCalledOnce();
  });

  it('renames and updates the list and current entry in place', async () => {
    mocks.getConversation.mockResolvedValue(convA);
    mocks.updateConversationTitle.mockResolvedValue({ ...convA, title: 'Renamed' });
    mocks.listConversations.mockResolvedValue([convA]);
    const store = useConversationStore();
    await store.fetchList(7);
    await store.fetchDetail(7, 1);
    await store.rename(7, 1, 'Renamed');
    expect(store.messagesError).toBeNull();
    expect(store.current?.title).toBe('Renamed');
    expect(store.items[0].title).toBe('Renamed');
  });

  it('deletes a conversation and drops it from the list/current', async () => {
    mocks.deleteConversation.mockResolvedValue(undefined);
    const store = useConversationStore();
    store.items = [convA, convB];
    store.current = convA;
    await store.remove(7, 1);
    expect(store.items.map((c) => c.id)).toEqual([2]);
    expect(store.current).toBeNull();
  });

  it('ignores a stale detail response for a switched conversation id', async () => {
    // detail for 1 resolves after user switched to 2
    let resolveA!: (v: unknown) => void;
    mocks.getConversation.mockImplementation((_w: number, id: number) => {
      if (id === 1) {
        return new Promise((r) => {
          resolveA = r;
        });
      }
      return Promise.resolve(convB);
    });

    const store = useConversationStore();
    const p1 = store.fetchDetail(7, 1);
    const p2 = store.fetchDetail(7, 2);
    await p2;
    resolveA(convA); // late response for conversation 1
    await p1;
    expect(store.current?.id).toBe(2);
  });

  it('ignores a stale messages response for a switched conversation', async () => {
    let resolveA!: (v: unknown) => void;
    mocks.getConversationMessages.mockImplementation((_w: number, id: number) => {
      if (id === 1) {
        return new Promise((r) => {
          resolveA = r;
        });
      }
      return Promise.resolve([{ id: 22, conversationId: 2, role: 'USER', content: 'x', status: 'SUCCESS' }]);
    });

    const store = useConversationStore();
    const p1 = store.fetchMessages(7, 1);
    const p2 = store.fetchMessages(7, 2);
    await p2;
    resolveA([{ id: 11, conversationId: 1, role: 'ASSISTANT', content: 'late', status: 'SUCCESS' }]);
    await p1;
    expect(store.messages.every((m) => m.conversationId === 2)).toBe(true);
  });
});