import client from './client';
import type {
  DocumentChunkPage,
  DocumentPage,
  DocumentRecord,
  DocumentRetryResponse,
  DocumentStatus,
  DocumentUploadResponse,
} from '@/types/document';

const basePath = (workspaceId: number, knowledgeBaseId: number) =>
  `/workspaces/${workspaceId}/knowledge-bases/${knowledgeBaseId}/documents`;

export function listDocuments(
  workspaceId: number,
  knowledgeBaseId: number,
  params: { page: number; size: number; status?: DocumentStatus }
): Promise<DocumentPage> {
  return client.get(basePath(workspaceId, knowledgeBaseId), { params });
}

export function getDocument(
  workspaceId: number,
  knowledgeBaseId: number,
  documentId: number
): Promise<DocumentRecord> {
  return client.get(`${basePath(workspaceId, knowledgeBaseId)}/${documentId}`);
}

export function uploadDocument(
  workspaceId: number,
  knowledgeBaseId: number,
  file: File,
  onUploadProgress?: (progress: number) => void
): Promise<DocumentUploadResponse> {
  const data = new FormData();
  data.append('file', file);
  return client.post(basePath(workspaceId, knowledgeBaseId), data, {
    headers: { 'Content-Type': undefined },
    onUploadProgress: (event) => {
      if (event.total) {
        onUploadProgress?.(Math.round((event.loaded * 100) / event.total));
      }
    },
  });
}

export function deleteDocument(
  workspaceId: number,
  knowledgeBaseId: number,
  documentId: number
): Promise<void> {
  return client.delete(`${basePath(workspaceId, knowledgeBaseId)}/${documentId}`);
}

export function retryDocument(
  workspaceId: number,
  knowledgeBaseId: number,
  documentId: number
): Promise<DocumentRetryResponse> {
  return client.post(`${basePath(workspaceId, knowledgeBaseId)}/${documentId}/retry`);
}

export function listDocumentChunks(
  workspaceId: number,
  knowledgeBaseId: number,
  documentId: number,
  params: { page: number; size: number }
): Promise<DocumentChunkPage> {
  return client.get(`${basePath(workspaceId, knowledgeBaseId)}/${documentId}/chunks`, { params });
}
