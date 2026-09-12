import { defineStore } from 'pinia';
import { ref } from 'vue';
import * as knowledgeBaseApi from '@/api/knowledgeBase';
import type {
  KnowledgeBase,
  KnowledgeBaseCreateRequest,
  KnowledgeBaseUpdateRequest,
} from '@/types/knowledgeBase';
import type { ApiError } from '@/types/api';

const DEFAULT_PAGE_SIZE = 20;

export const useKnowledgeBaseStore = defineStore('knowledgeBase', () => {
  const items = ref<KnowledgeBase[]>([]);
  const currentKnowledgeBase = ref<KnowledgeBase | null>(null);
  const page = ref(1);
  const size = ref(DEFAULT_PAGE_SIZE);
  const total = ref(0);
  const keyword = ref('');
  const listLoading = ref(false);
  const detailLoading = ref(false);
  const actionLoading = ref(false);
  const listError = ref<ApiError | null>(null);
  const detailError = ref<ApiError | null>(null);
  const actionError = ref<ApiError | null>(null);
  let listRequestSequence = 0;
  let detailRequestSequence = 0;

  function setListQuery(nextPage = page.value, nextKeyword = keyword.value) {
    page.value = Math.max(1, nextPage);
    keyword.value = nextKeyword;
  }

  async function fetchList(workspaceId: number, nextPage = page.value, nextKeyword = keyword.value) {
    const requestId = ++listRequestSequence;
    const identity = `${workspaceId}:${nextPage}:${size.value}:${nextKeyword}`;
    setListQuery(nextPage, nextKeyword);
    listLoading.value = true;
    listError.value = null;
    try {
      const response = await knowledgeBaseApi.listKnowledgeBases(workspaceId, {
        page: nextPage,
        size: size.value,
        keyword: nextKeyword || undefined,
      });
      if (requestId !== listRequestSequence || identity !== `${workspaceId}:${page.value}:${size.value}:${keyword.value}`) return;
      items.value = response.items;
      page.value = response.page;
      size.value = response.size;
      total.value = response.total;
    } catch (error) {
      if (requestId === listRequestSequence && identity === `${workspaceId}:${page.value}:${size.value}:${keyword.value}`) {
        listError.value = error as ApiError;
      }
    } finally {
      if (requestId === listRequestSequence && identity === `${workspaceId}:${page.value}:${size.value}:${keyword.value}`) {
        listLoading.value = false;
      }
    }
  }

  async function fetchDetail(workspaceId: number, knowledgeBaseId: number) {
    const requestId = ++detailRequestSequence;
    const identity = `${workspaceId}:${knowledgeBaseId}`;
    detailLoading.value = true;
    detailError.value = null;
    try {
      const response = await knowledgeBaseApi.getKnowledgeBase(workspaceId, knowledgeBaseId);
      if (requestId !== detailRequestSequence || identity !== `${workspaceId}:${knowledgeBaseId}`) return null;
      currentKnowledgeBase.value = response;
      return response;
    } catch (error) {
      if (requestId === detailRequestSequence && identity === `${workspaceId}:${knowledgeBaseId}`) {
        detailError.value = error as ApiError;
      }
      return null;
    } finally {
      if (requestId === detailRequestSequence && identity === `${workspaceId}:${knowledgeBaseId}`) {
        detailLoading.value = false;
      }
    }
  }

  async function create(workspaceId: number, data: KnowledgeBaseCreateRequest) {
    actionLoading.value = true;
    actionError.value = null;
    try {
      const response = await knowledgeBaseApi.createKnowledgeBase(workspaceId, data);
      await fetchList(workspaceId, 1, keyword.value);
      return response;
    } catch (error) {
      actionError.value = error as ApiError;
      throw error;
    } finally {
      actionLoading.value = false;
    }
  }

  async function update(workspaceId: number, knowledgeBaseId: number, data: KnowledgeBaseUpdateRequest) {
    actionLoading.value = true;
    actionError.value = null;
    try {
      const response = await knowledgeBaseApi.updateKnowledgeBase(workspaceId, knowledgeBaseId, data);
      if (currentKnowledgeBase.value?.id === knowledgeBaseId) {
        currentKnowledgeBase.value = response;
      }
      await fetchList(workspaceId, page.value, keyword.value);
      return response;
    } catch (error) {
      actionError.value = error as ApiError;
      throw error;
    } finally {
      actionLoading.value = false;
    }
  }

  async function remove(workspaceId: number, knowledgeBaseId: number) {
    actionLoading.value = true;
    actionError.value = null;
    try {
      await knowledgeBaseApi.deleteKnowledgeBase(workspaceId, knowledgeBaseId);
      await fetchList(workspaceId, page.value, keyword.value);
      return true;
    } catch (error) {
      actionError.value = error as ApiError;
      throw error;
    } finally {
      actionLoading.value = false;
    }
  }

  return {
    items,
    currentKnowledgeBase,
    page,
    size,
    total,
    keyword,
    listLoading,
    detailLoading,
    actionLoading,
    listError,
    detailError,
    actionError,
    setListQuery,
    fetchList,
    fetchDetail,
    create,
    update,
    remove,
  };
});
