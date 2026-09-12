import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  listDocuments: vi.fn(),
  getDocument: vi.fn(),
  uploadDocument: vi.fn(),
  retryDocument: vi.fn(),
  deleteDocument: vi.fn(),
  listDocumentChunks: vi.fn(),
}));

vi.mock('@/api/document', () => mocks);

import { setActivePinia, createPinia } from 'pinia';
import { useDocumentStore } from '@/stores/document';

describe('document store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    vi.useRealTimers();
  });

  it('refreshes list after 202 upload and does not start polling', async () => {
    vi.useFakeTimers();
    const store = useDocumentStore();
    const file = new File(['hello'], 'fixture.txt', { type: 'text/plain' });
    mocks.uploadDocument.mockResolvedValue({ documentId: 4, taskId: 5, fileName: file.name, status: 'PENDING' });
    mocks.listDocuments.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 });

    await store.upload(1, 2, file);

    expect(store.uploadAccepted?.status).toBe('PENDING');
    expect(mocks.listDocuments).toHaveBeenCalledOnce();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('starts one bounded detail poll for PROCESSING and stops at terminal state', async () => {
    vi.useFakeTimers();
    const store = useDocumentStore();
    mocks.getDocument
      .mockResolvedValueOnce({ id: 4, status: 'PROCESSING' })
      .mockResolvedValueOnce({ id: 4, status: 'COMPLETED' });

    await store.fetchDetail(1, 2, 4);
    store.startPolling(1, 2, 4);
    store.startPolling(1, 2, 4);
    expect(vi.getTimerCount()).toBe(1);
    await vi.advanceTimersByTimeAsync(2000);
    expect(mocks.getDocument).toHaveBeenCalledTimes(2);
    expect(store.currentDocument?.status).toBe('COMPLETED');
    expect(vi.getTimerCount()).toBe(0);
  });

  it('only retries failed documents and accepts the current 200 response contract', async () => {
    const store = useDocumentStore();
    mocks.retryDocument.mockResolvedValue({ documentId: 4, taskId: 9, status: 'PENDING' });
    mocks.getDocument.mockResolvedValue({ id: 4, status: 'PENDING' });

    await store.fetchDetail(1, 2, 4);
    await store.retry(1, 2, 4);
    expect(mocks.retryDocument).toHaveBeenCalledWith(1, 2, 4);

    mocks.retryDocument.mockClear();
    store.currentDocument!.status = 'COMPLETED';
    expect(store.currentDocument?.status).not.toBe('FAILED');
    expect(mocks.retryDocument).not.toHaveBeenCalled();
  });

  it('does not request chunks for non-completed documents and preserves nullable token counts', async () => {
    const store = useDocumentStore();
    mocks.getDocument.mockResolvedValue({ id: 4, status: 'PENDING' });
    await store.fetchDetail(1, 2, 4);
    await store.fetchChunks(1, 2, 4);
    expect(mocks.listDocumentChunks).not.toHaveBeenCalled();

    store.currentDocument!.status = 'COMPLETED';
    mocks.listDocumentChunks.mockResolvedValue({ items: [{ id: 1, tokenCount: null, content: '<text>' }], page: 1, size: 20, total: 1 });
    await store.fetchChunks(1, 2, 4);
    expect(store.chunks[0].tokenCount).toBeNull();
  });
});
