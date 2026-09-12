import { defineStore } from 'pinia';
import { ref } from 'vue';
import * as documentApi from '@/api/document';
import type {
  DocumentChunk,
  DocumentRecord,
  DocumentStatus,
  DocumentUploadResponse,
} from '@/types/document';
import type { ApiError } from '@/types/api';

const DEFAULT_PAGE_SIZE = 20;
const POLL_INTERVAL_MS = 2000;
const MAX_POLL_REQUESTS = 60;
const MAX_POLL_DURATION_MS = 120000;

export const useDocumentStore = defineStore('document', () => {
  const items = ref<DocumentRecord[]>([]);
  const currentDocument = ref<DocumentRecord | null>(null);
  const chunks = ref<DocumentChunk[]>([]);
  const page = ref(1);
  const size = ref(DEFAULT_PAGE_SIZE);
  const total = ref(0);
  const statusFilter = ref<DocumentStatus | undefined>();
  const chunkPage = ref(1);
  const chunkSize = ref(DEFAULT_PAGE_SIZE);
  const chunkTotal = ref(0);
  const listLoading = ref(false);
  const detailLoading = ref(false);
  const chunksLoading = ref(false);
  const uploadLoading = ref(false);
  const uploadProgress = ref(0);
  const actionLoading = ref(false);
  const listError = ref<ApiError | null>(null);
  const detailError = ref<ApiError | null>(null);
  const chunksError = ref<ApiError | null>(null);
  const actionError = ref<ApiError | null>(null);
  const uploadAccepted = ref<DocumentUploadResponse | null>(null);
  let activeIdentity = '';
  let pollTimer: ReturnType<typeof setTimeout> | null = null;
  let pollStartedAt = 0;
  let pollRequests = 0;

  function identity(workspaceId: number, knowledgeBaseId: number, documentId?: number) {
    return documentId === undefined
      ? `${workspaceId}:${knowledgeBaseId}`
      : `${workspaceId}:${knowledgeBaseId}:${documentId}`;
  }

  function isPollable(status?: DocumentStatus) {
    return status === 'PENDING' || status === 'PROCESSING';
  }

  function stopPolling() {
    if (pollTimer) clearTimeout(pollTimer);
    pollTimer = null;
    pollStartedAt = 0;
    pollRequests = 0;
  }

  async function fetchList(
    workspaceId: number,
    knowledgeBaseId: number,
    nextPage = page.value,
    nextStatus = statusFilter.value
  ) {
    const requestIdentity = identity(workspaceId, knowledgeBaseId);
    page.value = Math.max(1, nextPage);
    statusFilter.value = nextStatus;
    listLoading.value = true;
    listError.value = null;
    try {
      const response = await documentApi.listDocuments(workspaceId, knowledgeBaseId, {
        page: page.value,
        size: size.value,
        status: statusFilter.value,
      });
      if (requestIdentity !== identity(workspaceId, knowledgeBaseId)) return;
      items.value = response.items;
      page.value = response.page;
      size.value = response.size;
      total.value = response.total;
    } catch (error) {
      if (requestIdentity === identity(workspaceId, knowledgeBaseId)) listError.value = error as ApiError;
    } finally {
      if (requestIdentity === identity(workspaceId, knowledgeBaseId)) listLoading.value = false;
    }
  }

  async function fetchDetail(workspaceId: number, knowledgeBaseId: number, documentId: number) {
    const requestIdentity = identity(workspaceId, knowledgeBaseId, documentId);
    activeIdentity = requestIdentity;
    detailLoading.value = true;
    detailError.value = null;
    try {
      const response = await documentApi.getDocument(workspaceId, knowledgeBaseId, documentId);
      if (activeIdentity !== requestIdentity) return null;
      currentDocument.value = response;
      return response;
    } catch (error) {
      if (activeIdentity === requestIdentity) detailError.value = error as ApiError;
      return null;
    } finally {
      if (activeIdentity === requestIdentity) detailLoading.value = false;
    }
  }

  async function upload(workspaceId: number, knowledgeBaseId: number, file: File) {
    if (uploadLoading.value) return null;
    uploadLoading.value = true;
    uploadProgress.value = 0;
    actionError.value = null;
    uploadAccepted.value = null;
    try {
      const response = await documentApi.uploadDocument(workspaceId, knowledgeBaseId, file, (progress) => {
        uploadProgress.value = progress;
      });
      uploadAccepted.value = response;
      await fetchList(workspaceId, knowledgeBaseId, 1, statusFilter.value);
      return response;
    } catch (error) {
      actionError.value = error as ApiError;
      throw error;
    } finally {
      uploadLoading.value = false;
    }
  }

  async function retry(workspaceId: number, knowledgeBaseId: number, documentId: number) {
    actionLoading.value = true;
    actionError.value = null;
    try {
      const response = await documentApi.retryDocument(workspaceId, knowledgeBaseId, documentId);
      const detail = await fetchDetail(workspaceId, knowledgeBaseId, documentId);
      if (detail && isPollable(detail.status)) startPolling(workspaceId, knowledgeBaseId, documentId);
      return response;
    } catch (error) {
      actionError.value = error as ApiError;
      throw error;
    } finally {
      actionLoading.value = false;
    }
  }

  async function remove(workspaceId: number, knowledgeBaseId: number, documentId: number) {
    stopPolling();
    actionLoading.value = true;
    actionError.value = null;
    try {
      await documentApi.deleteDocument(workspaceId, knowledgeBaseId, documentId);
      if (activeIdentity === identity(workspaceId, knowledgeBaseId, documentId)) {
        currentDocument.value = null;
      }
      await fetchList(workspaceId, knowledgeBaseId, page.value, statusFilter.value);
    } catch (error) {
      actionError.value = error as ApiError;
      throw error;
    } finally {
      actionLoading.value = false;
    }
  }

  async function fetchChunks(
    workspaceId: number,
    knowledgeBaseId: number,
    documentId: number,
    nextPage = chunkPage.value
  ) {
    const requestIdentity = identity(workspaceId, knowledgeBaseId, documentId);
    if (activeIdentity !== requestIdentity || currentDocument.value?.status !== 'COMPLETED') return;
    chunksLoading.value = true;
    chunksError.value = null;
    try {
      const response = await documentApi.listDocumentChunks(workspaceId, knowledgeBaseId, documentId, {
        page: Math.max(1, nextPage),
        size: chunkSize.value,
      });
      if (activeIdentity !== requestIdentity || currentDocument.value?.status !== 'COMPLETED') return;
      chunks.value = response.items;
      chunkPage.value = response.page;
      chunkSize.value = response.size;
      chunkTotal.value = response.total;
    } catch (error) {
      if (activeIdentity === requestIdentity) chunksError.value = error as ApiError;
    } finally {
      if (activeIdentity === requestIdentity) chunksLoading.value = false;
    }
  }

  async function pollOnce(workspaceId: number, knowledgeBaseId: number, documentId: number, requestIdentity: string) {
    if (activeIdentity !== requestIdentity) return;
    if (pollRequests >= MAX_POLL_REQUESTS || Date.now() - pollStartedAt >= MAX_POLL_DURATION_MS) {
      stopPolling();
      return;
    }
    pollRequests += 1;
    const detail = await fetchDetail(workspaceId, knowledgeBaseId, documentId);
    if (activeIdentity !== requestIdentity || !detail || !isPollable(detail.status)) {
      if (detail && !isPollable(detail.status)) stopPolling();
      return;
    }
    pollTimer = setTimeout(() => pollOnce(workspaceId, knowledgeBaseId, documentId, requestIdentity), POLL_INTERVAL_MS);
  }

  function startPolling(workspaceId: number, knowledgeBaseId: number, documentId: number) {
    const requestIdentity = identity(workspaceId, knowledgeBaseId, documentId);
    if (activeIdentity !== requestIdentity || !isPollable(currentDocument.value?.status) || pollTimer) return;
    pollStartedAt = Date.now();
    pollRequests = 0;
    pollTimer = setTimeout(() => pollOnce(workspaceId, knowledgeBaseId, documentId, requestIdentity), POLL_INTERVAL_MS);
  }

  function clearDetail() {
    stopPolling();
    activeIdentity = '';
    currentDocument.value = null;
    chunks.value = [];
    chunkTotal.value = 0;
  }

  return {
    items,
    currentDocument,
    chunks,
    page,
    size,
    total,
    statusFilter,
    chunkPage,
    chunkSize,
    chunkTotal,
    listLoading,
    detailLoading,
    chunksLoading,
    uploadLoading,
    uploadProgress,
    actionLoading,
    listError,
    detailError,
    chunksError,
    actionError,
    uploadAccepted,
    fetchList,
    fetchDetail,
    upload,
    retry,
    remove,
    fetchChunks,
    startPolling,
    stopPolling,
    clearDetail,
  };
});
