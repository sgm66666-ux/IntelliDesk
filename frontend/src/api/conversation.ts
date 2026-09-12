import client from './client';
import type {
  Conversation,
  ChatMessage,
  ConversationCreatePayload,
  ConversationUpdatePayload,
} from '@/types/conversation';

const basePath = (workspaceId: number) => `/workspaces/${workspaceId}/conversations`;

export function listConversations(workspaceId: number): Promise<Conversation[]> {
  return client.get(basePath(workspaceId));
}

export function getConversation(
  workspaceId: number,
  conversationId: number
): Promise<Conversation> {
  return client.get(`${basePath(workspaceId)}/${conversationId}`);
}

export function createConversation(
  workspaceId: number,
  data: ConversationCreatePayload
): Promise<Conversation> {
  return client.post(basePath(workspaceId), data);
}

export function updateConversationTitle(
  workspaceId: number,
  conversationId: number,
  data: ConversationUpdatePayload
): Promise<Conversation> {
  return client.patch(`${basePath(workspaceId)}/${conversationId}`, data);
}

export function deleteConversation(workspaceId: number, conversationId: number): Promise<void> {
  return client.delete(`${basePath(workspaceId)}/${conversationId}`);
}

export function getConversationMessages(
  workspaceId: number,
  conversationId: number
): Promise<ChatMessage[]> {
  return client.get(`${basePath(workspaceId)}/${conversationId}/messages`);
}