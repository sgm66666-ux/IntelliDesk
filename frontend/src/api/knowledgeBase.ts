import client from './client';
import type {
  KnowledgeBase,
  KnowledgeBaseCreateRequest,
  KnowledgeBaseUpdateRequest,
  PageResponse,
} from '@/types/knowledgeBase';

const basePath = (workspaceId: number) => `/workspaces/${workspaceId}/knowledge-bases`;

export function listKnowledgeBases(
  workspaceId: number,
  params: { page: number; size: number; keyword?: string }
): Promise<PageResponse<KnowledgeBase>> {
  return client.get(basePath(workspaceId), { params });
}

export function getKnowledgeBase(workspaceId: number, knowledgeBaseId: number): Promise<KnowledgeBase> {
  return client.get(`${basePath(workspaceId)}/${knowledgeBaseId}`);
}

export function createKnowledgeBase(
  workspaceId: number,
  data: KnowledgeBaseCreateRequest
): Promise<KnowledgeBase> {
  return client.post(basePath(workspaceId), data);
}

export function updateKnowledgeBase(
  workspaceId: number,
  knowledgeBaseId: number,
  data: KnowledgeBaseUpdateRequest
): Promise<KnowledgeBase> {
  return client.put(`${basePath(workspaceId)}/${knowledgeBaseId}`, data);
}

export function deleteKnowledgeBase(workspaceId: number, knowledgeBaseId: number): Promise<void> {
  return client.delete(`${basePath(workspaceId)}/${knowledgeBaseId}`);
}
