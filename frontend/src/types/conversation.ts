export interface Conversation {
  id: number;
  workspaceId: number;
  userId: number;
  title: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export type ChatRole = 'SYSTEM' | 'USER' | 'ASSISTANT' | 'TOOL';

export type ChatMessageStatus = 'GENERATING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';

/** citation cell serialized in chat_message.citation (jsonb → JSON string). */
export interface CitationItem {
  citationId: number;
  documentId: number;
  documentName?: string;
  chunkId: number;
  content?: string;
  score?: number;
  pageNumber?: number;
}

export interface TokenUsageItem {
  prompt_tokens?: number;
  completion_tokens?: number;
  total_tokens?: number;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
}

export interface ChatMessage {
  id: number;
  conversationId: number;
  role: ChatRole;
  content: string;
  status: ChatMessageStatus;
  model?: string;
  errorCode?: string;
  errorMessage?: string;
  /** jsonb column read as a JSON string; may be null or already an array. */
  citation?: string | null;
  /** jsonb column read as a JSON string; may be null or already an object. */
  tokenUsage?: string | null;
  sequenceNo?: number;
  createdAt?: string;
}

export interface ConversationCreatePayload {
  title?: string;
}

export interface ConversationUpdatePayload {
  title: string;
}