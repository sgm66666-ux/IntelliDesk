import type { PageResponse } from './knowledgeBase';

export type DocumentStatus =
  | 'UPLOADING'
  | 'PENDING'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'FAILED'
  | 'DELETING';

export interface DocumentTask {
  id: number;
  status: string;
  attemptCount?: number;
  maxAttempts?: number;
  lastErrorCode?: string | null;
  lastErrorMessage?: string | null;
  createdAt?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
}

export interface DocumentRecord {
  id: number;
  knowledgeBaseId: number;
  fileName: string;
  fileExtension?: string | null;
  contentType?: string | null;
  fileSize?: number | null;
  checksumSha256?: string | null;
  status: DocumentStatus;
  chunkStrategy?: string | null;
  chunkSize?: number | null;
  chunkOverlap?: number | null;
  parserMetadata?: Record<string, unknown> | null;
  failureCode?: string | null;
  failureMessage?: string | null;
  latestTask?: DocumentTask | null;
  createdBy?: number | null;
  completedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface DocumentUploadResponse {
  documentId: number;
  taskId: number;
  fileName: string;
  fileSize: number;
  checksumSha256?: string | null;
  status: DocumentStatus;
  createdAt?: string | null;
}

export interface DocumentRetryResponse {
  documentId: number;
  taskId: number;
  status: DocumentStatus;
  createdAt?: string | null;
}

export interface DocumentChunk {
  id: number;
  documentId: number;
  chunkIndex: number;
  content: string;
  characterCount?: number | null;
  tokenCount?: number | null;
  pageStart?: number | null;
  pageEnd?: number | null;
  sectionPath?: string | null;
  sourceMetadata?: Record<string, unknown> | null;
  createdAt?: string | null;
}

export type DocumentPage = PageResponse<DocumentRecord>;
export type DocumentChunkPage = PageResponse<DocumentChunk>;
