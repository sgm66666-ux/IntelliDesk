import { defineStore } from 'pinia';
import { ref } from 'vue';
import * as conversationApi from '@/api/conversation';
import type { Conversation, ChatMessage, ConversationCreatePayload } from '@/types/conversation';
import type { ApiError } from '@/types/api';

/**
 * Conversation domain store.
 *
 * Holds the workspace-scoped conversation list and the currently-open
 * conversation's message history. Uses per-request identity guards so that a
 * slow response for conversation A never overwrites the state of conversation B.
 */
export const useConversationStore = defineStore('conversation', () => {
  const items = ref<Conversation[]>([]);
  const current = ref<Conversation | null>(null);
  const messages = ref<ChatMessage[]>([]);
  const listLoading = ref(false);
  const detailLoading = ref(false);
  const messagesLoading = ref(false);
  const actionLoading = ref(false);
  const listError = ref<ApiError | null>(null);
  const detailError = ref<ApiError | null>(null);
  const messagesError = ref<ApiError | null>(null);
  const actionError = ref<ApiError | null>(null);

  let listSequence = 0;
  let detailSequence = 0;
  let messagesSequence = 0;

  function reset(): void {
    items.value = [];
    current.value = null;
    messages.value = [];
    listError.value = null;
    detailError.value = null;
    messagesError.value = null;
  }

  async function fetchList(workspaceId: number): Promise<Conversation[]> {
    const requestId = ++listSequence;
    listLoading.value = true;
    listError.value = null;
    try {
      const result = await conversationApi.listConversations(workspaceId);
      if (requestId !== listSequence) return result;
      items.value = result;
      return result;
    } catch (error) {
      if (requestId === listSequence) listError.value = error as ApiError;
      throw error;
    } finally {
      if (requestId === listSequence) listLoading.value = false;
    }
  }

  async function fetchDetail(
    workspaceId: number,
    conversationId: number
  ): Promise<Conversation | null> {
    const requestId = ++detailSequence;
    const identity = `${workspaceId}:${conversationId}`;
    detailLoading.value = true;
    detailError.value = null;
    try {
      const result = await conversationApi.getConversation(workspaceId, conversationId);
      if (requestId !== detailSequence || identity !== `${workspaceId}:${conversationId}`) return null;
      current.value = result;
      return result;
    } catch (error) {
      if (
        requestId === detailSequence &&
        identity === `${workspaceId}:${conversationId}`
      ) {
        detailError.value = error as ApiError;
      }
      return null;
    } finally {
      if (
        requestId === detailSequence &&
        identity === `${workspaceId}:${conversationId}`
      ) {
        detailLoading.value = false;
      }
    }
  }

  async function fetchMessages(
    workspaceId: number,
    conversationId: number
  ): Promise<ChatMessage[]> {
    const requestId = ++messagesSequence;
    const identity = `${workspaceId}:${conversationId}`;
    messagesLoading.value = true;
    messagesError.value = null;
    try {
      const result = await conversationApi.getConversationMessages(workspaceId, conversationId);
      if (
        requestId !== messagesSequence ||
        identity !== `${workspaceId}:${conversationId}`
      ) {
        return result;
      }
      messages.value = result;
      return result;
    } catch (error) {
      if (
        requestId === messagesSequence &&
        identity === `${workspaceId}:${conversationId}`
      ) {
        messagesError.value = error as ApiError;
      }
      throw error;
    } finally {
      if (
        requestId === messagesSequence &&
        identity === `${workspaceId}:${conversationId}`
      ) {
        messagesLoading.value = false;
      }
    }
  }

  async function create(
    workspaceId: number,
    payload: ConversationCreatePayload
  ): Promise<Conversation> {
    actionLoading.value = true;
    actionError.value = null;
    try {
      const result = await conversationApi.createConversation(workspaceId, payload);
      await fetchList(workspaceId).catch(() => undefined);
      current.value = result;
      return result;
    } catch (error) {
      actionError.value = error as ApiError;
      throw error;
    } finally {
      actionLoading.value = false;
    }
  }

  async function rename(
    workspaceId: number,
    conversationId: number,
    title: string
  ): Promise<Conversation> {
    actionLoading.value = true;
    actionError.value = null;
    try {
      const result = await conversationApi.updateConversationTitle(
        workspaceId,
        conversationId,
        { title }
      );
      if (current.value?.id === conversationId) current.value = result;
      const idx = items.value.findIndex((c) => c.id === conversationId);
      if (idx !== -1) items.value[idx] = result;
      return result;
    } catch (error) {
      actionError.value = error as ApiError;
      throw error;
    } finally {
      actionLoading.value = false;
    }
  }

  async function remove(workspaceId: number, conversationId: number): Promise<boolean> {
    actionLoading.value = true;
    actionError.value = null;
    try {
      await conversationApi.deleteConversation(workspaceId, conversationId);
      items.value = items.value.filter((c) => c.id !== conversationId);
      if (current.value?.id === conversationId) current.value = null;
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
    current,
    messages,
    listLoading,
    detailLoading,
    messagesLoading,
    actionLoading,
    listError,
    detailError,
    messagesError,
    actionError,
    reset,
    fetchList,
    fetchDetail,
    fetchMessages,
    create,
    rename,
    remove,
  };
});