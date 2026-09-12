import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}));

vi.mock('@/api/client', () => ({ default: mocks }));

import * as knowledgeBaseApi from '@/api/knowledgeBase';
import * as documentApi from '@/api/document';

describe('knowledge base API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('uses all five knowledge base paths and DTO fields', async () => {
    mocks.get.mockResolvedValue({});
    mocks.post.mockResolvedValue({});
    mocks.put.mockResolvedValue({});
    mocks.delete.mockResolvedValue(undefined);
    const data = { name: 'KB', description: 'desc', chunkStrategy: 'RECURSIVE' as const, chunkSize: 1000, chunkOverlap: 150 };

    await knowledgeBaseApi.listKnowledgeBases(7, { page: 1, size: 20 });
    await knowledgeBaseApi.getKnowledgeBase(7, 8);
    await knowledgeBaseApi.createKnowledgeBase(7, data);
    await knowledgeBaseApi.updateKnowledgeBase(7, 8, data);
    await knowledgeBaseApi.deleteKnowledgeBase(7, 8);

    expect(mocks.get).toHaveBeenNthCalledWith(1, '/workspaces/7/knowledge-bases', { params: { page: 1, size: 20 } });
    expect(mocks.get).toHaveBeenNthCalledWith(2, '/workspaces/7/knowledge-bases/8');
    expect(mocks.post).toHaveBeenCalledWith('/workspaces/7/knowledge-bases', data);
    expect(mocks.put).toHaveBeenCalledWith('/workspaces/7/knowledge-bases/8', data);
    expect(mocks.delete).toHaveBeenCalledWith('/workspaces/7/knowledge-bases/8');
    expect(Object.keys(data)).toEqual(['name', 'description', 'chunkStrategy', 'chunkSize', 'chunkOverlap']);
  });
});

describe('document API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('uses the complete parent path for all six document operations', async () => {
    mocks.get.mockResolvedValue({});
    mocks.post.mockResolvedValue({});
    mocks.delete.mockResolvedValue(undefined);
    const file = new File(['hello'], 'fixture.txt', { type: 'text/plain' });

    await documentApi.listDocuments(1, 2, { page: 1, size: 20, status: 'PENDING' });
    await documentApi.getDocument(1, 2, 3);
    await documentApi.uploadDocument(1, 2, file);
    await documentApi.deleteDocument(1, 2, 3);
    await documentApi.retryDocument(1, 2, 3);
    await documentApi.listDocumentChunks(1, 2, 3, { page: 1, size: 20 });

    expect(mocks.get).toHaveBeenNthCalledWith(1, '/workspaces/1/knowledge-bases/2/documents', { params: { page: 1, size: 20, status: 'PENDING' } });
    expect(mocks.get).toHaveBeenNthCalledWith(2, '/workspaces/1/knowledge-bases/2/documents/3');
    expect(mocks.delete).toHaveBeenCalledWith('/workspaces/1/knowledge-bases/2/documents/3');
    expect(mocks.post).toHaveBeenNthCalledWith(1, '/workspaces/1/knowledge-bases/2/documents', expect.any(FormData), expect.any(Object));
    expect(mocks.post).toHaveBeenNthCalledWith(2, '/workspaces/1/knowledge-bases/2/documents/3/retry');
    expect(mocks.get).toHaveBeenNthCalledWith(3, '/workspaces/1/knowledge-bases/2/documents/3/chunks', { params: { page: 1, size: 20 } });
    const formData = mocks.post.mock.calls[0][1] as FormData;
    expect(formData.get('file')).toBe(file);
  });
});
