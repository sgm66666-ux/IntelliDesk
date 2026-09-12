export type ChunkStrategy = 'FIXED_SIZE' | 'RECURSIVE';

export interface KnowledgeBase {
  id: number;
  workspaceId: number;
  name: string;
  description?: string | null;
  chunkStrategy: ChunkStrategy;
  chunkSize: number;
  chunkOverlap: number;
  status?: string;
  createdBy?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface KnowledgeBaseRequest {
  name: string;
  description?: string;
  chunkStrategy: ChunkStrategy;
  chunkSize: number;
  chunkOverlap: number;
}

export type KnowledgeBaseCreateRequest = KnowledgeBaseRequest;
export type KnowledgeBaseUpdateRequest = KnowledgeBaseRequest;

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}
