<template>
  <MainLayout>
    <div class="chat-page">
      <div class="chat-page__sidebar">
        <div class="chat-page__sidebar-header">
          <span class="chat-page__title">Conversations</span>
          <el-button size="small" type="primary" :loading="convoStore.actionLoading" @click="createConversation">
            + New
          </el-button>
        </div>
        <LoadingSpinner v-if="convoStore.listLoading" :size="24" text="Loading…" />
        <ul v-else-if="convoStore.items.length" class="chat-page__list">
          <li
            v-for="c in convoStore.items"
            :key="c.id"
            :class="['chat-page__item', { 'chat-page__item--active': c.id === conversationId }]"
            @click="openConversation(c.id)"
          >
            <span class="chat-page__item-txt">{{ c.title || 'Untitled' }}</span>
            <el-button
              text
              type="danger"
              size="small"
              class="chat-page__item-del"
              @click.stop="deleteConversation(c.id)"
            >
              <el-icon><Delete /></el-icon>
            </el-button>
          </li>
        </ul>
        <div v-else class="chat-page__placeholder">No conversations yet</div>
      </div>

      <div class="chat-page__main">
        <!-- Header -->
        <div class="chat-page__tabs">
          <ModeToggle v-model="mode" :disabled="chatStore.isStreaming" />
          <KnowledgeBaseSelector
            v-if="mode === 'rag'"
            ref="kbSelectorRef"
            :items="kbItems"
            :loading="kbLoading"
            :disabled="chatStore.isStreaming"
          />
        </div>

        <!-- Messages -->
        <div ref="scrollerRef" class="chat-page__messages">
          <ErrorMessage
            v-if="convoStore.messagesError"
            :message="convoStore.messagesError.message"
            :retry="true"
            :code="convoStore.messagesError.code"
            :trace-id="convoStore.messagesError.traceId"
            @retry="loadMessages"
          />
          <template v-else>
            <div v-if="items.length === 0" class="chat-page__empty">
              <el-empty :description="emptyHint" />
            </div>
            <div
              v-for="(item, idx) in items"
              :key="`${item.kind}-${item.message.id ?? item.message.createdAt ?? idx}`"
              class="chat-page__msg-row"
              :class="{ 'chat-page__msg-row--user': item.kind === 'user' }"
            >
              <!-- User -->
              <div v-if="item.kind === 'user'" class="chat-page__bubble chat-page__bubble--user">
                <BotIcon /><span>{{ item.message.content }}</span>
              </div>

              <!-- Tool result (persisted) -->
              <div v-else-if="item.kind === 'tool'" class="chat-page__tool-item">
                <ToolCard :item="toolFromMessage(item.message)" />
              </div>

              <!-- Assistant -->
              <div v-else class="chat-page__bubble chat-page__bubble--assistant">
                <AssistantAnswer
                  :content="item.message.content"
                  :citations="item.citations"
                  :streaming="!!item.live && !item.error"
                >
                  <template #tools>
                    <div v-if="item.toolTrace?.length" class="chat-page__trace">
                      <ToolCard v-for="t in item.toolTrace" :key="t.key" :item="t" />
                    </div>
                    <div
                      v-if="item.live && item.error"
                      class="chat-page__interrupted"
                    >
                      ⚠ {{ item.error.message }}
                      <el-button size="small" type="primary" link @click="loadMessages">
                        Reload conversation
                      </el-button>
                    </div>
                  </template>
                </AssistantAnswer>
              </div>
            </div>
          </template>
        </div>

        <!-- Composer -->
        <div class="chat-page__composer">
          <ChatComposer
            :disabled="composerDisabled"
            :streaming="chatStore.isStreaming"
            @send="onSend"
            @stop="onStop"
          />
        </div>
      </div>
    </div>
  </MainLayout>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ChatDotRound as BotIcon, Delete } from '@element-plus/icons-vue';
import MainLayout from '@/layouts/MainLayout.vue';
import LoadingSpinner from '@/components/common/LoadingSpinner.vue';
import ErrorMessage from '@/components/common/ErrorMessage.vue';
import ModeToggle from '@/components/chat/ModeToggle.vue';
import KnowledgeBaseSelector from '@/components/chat/KnowledgeBaseSelector.vue';
import ChatComposer from '@/components/chat/ChatComposer.vue';
import AssistantAnswer from '@/components/chat/AssistantAnswer.vue';
import ToolCard from '@/components/chat/ToolCard.vue';
import { useConversationStore } from '@/stores/conversation';
import { useChatStore } from '@/stores/chat';
import { listKnowledgeBases } from '@/api/knowledgeBase';
import { buildTimeline, parseToolTrace, type DisplayItem } from '@/lib/message';
import type { ChatMessage } from '@/types/conversation';
import type { ChatMode, ToolTraceItem } from '@/types/chat';
import type { KnowledgeBase } from '@/types/knowledgeBase';

const route = useRoute();
const router = useRouter();
const convoStore = useConversationStore();
const chatStore = useChatStore();

const workspaceId = computed(() => Number(route.params.workspaceId));
const conversationId = computed(() => Number(route.params.conversationId));

const mode = ref<ChatMode>('rag');
const kbSelectorRef = ref<InstanceType<typeof KnowledgeBaseSelector> | null>(null);
const kbItems = ref<KnowledgeBase[]>([]);
const kbLoading = ref(false);
const scrollerRef = ref<HTMLElement | null>(null);

const optimisticUser = ref<string | null>(null);
const liveAssist = ref<{
  content: string;
  citations: import('@/types/conversation').CitationItem[];
  toolTrace: ToolTraceItem[];
  status: 'streaming' | 'interrupted' | 'failed';
  error?: { message: string };
} | null>(null);
let traceKeySeq = 0;

const items = computed<DisplayItem[]>(() => {
  const timeline = buildTimeline(convoStore.messages);
  if (optimisticUser.value) {
    timeline.push({
      kind: 'user',
      message: {
        id: -Date.now(),
        conversationId: conversationId.value,
        role: 'USER',
        content: optimisticUser.value,
        status: 'SUCCESS',
      } as ChatMessage,
    });
  }
  if (liveAssist.value) {
    const live = liveAssist.value;
    timeline.push({
      kind: 'assistant',
      message: {
        id: -Date.now() - 1,
        conversationId: conversationId.value,
        role: 'ASSISTANT',
        content: live.content,
        status: live.status === 'streaming' ? 'GENERATING' : 'SUCCESS',
      } as ChatMessage,
      citations: live.citations,
      usage: null,
      live: true,
      toolTrace: live.toolTrace,
      error: live.error ?? null,
    });
  }
  return timeline;
});

const composerDisabled = computed(() => {
  if (chatStore.isStreaming) return true;
  if (mode.value === 'rag') {
    const selected = kbSelectorRef.value?.selected ?? [];
    return selected.length === 0;
  }
  return false;
});

const emptyHint = computed(() =>
  mode.value === 'rag'
    ? 'Ask about documents in your selected knowledge bases.'
    : 'Ask the built-in agent to search or compute for you.'
);

function valid() {
  return (
    Number.isSafeInteger(workspaceId.value) &&
    workspaceId.value > 0 &&
    Number.isSafeInteger(conversationId.value) &&
    conversationId.value > 0
  );
}

async function loadKBs() {
  if (!Number.isSafeInteger(workspaceId.value) || workspaceId.value <= 0 || mode.value !== 'rag') return;
  kbLoading.value = true;
  try {
    const res = await listKnowledgeBases(workspaceId.value, { page: 1, size: 100 });
    kbItems.value = res.items;
    if (kbSelectorRef.value?.selected.length === 0) {
      kbSelectorRef.value.selected = res.items.map((kb) => kb.id);
    }
  } catch {
    kbItems.value = [];
  } finally {
    kbLoading.value = false;
  }
}

function loadMessages() {
  if (valid()) {
    convoStore.fetchMessages(workspaceId.value, conversationId.value).catch(() => undefined);
  }
}

async function bootstrapConversation() {
  chatStore.stop();
  optimisticUser.value = null;
  liveAssist.value = null;
  if (valid()) {
    await convoStore.fetchDetail(workspaceId.value, conversationId.value).catch(() => undefined);
    await convoStore.fetchList(workspaceId.value).catch(() => undefined);
    loadMessages();
  }
}

async function onSend(query: string) {
  if (!valid()) return;
  if (mode.value === 'rag') {
    const selected = kbSelectorRef.value?.selected ?? [];
    if (kbItems.value.length === 0) {
      ElMessage.warning('Create a knowledge base first to use RAG chat.');
      return;
    }
    if (selected.length === 0) {
      ElMessage.warning('Select at least one knowledge base to search.');
      return;
    }
    startStream(query, mode.value, selected);
  } else {
    startStream(query, 'agent');
  }
}

function startStream(query: string, streamMode: ChatMode, kbIds?: number[]) {
  optimisticUser.value = query;
  liveAssist.value = {
    content: '',
    citations: [],
    toolTrace: [],
    status: 'streaming',
  };

  const started = chatStore.send({
    workspaceId: workspaceId.value,
    conversationId: conversationId.value,
    mode: streamMode,
    query,
    kbIds,
    handlers: {
      onStart: () => {},
      onToken: ({ delta }) => {
        if (liveAssist.value) liveAssist.value.content += delta;
      },
      onCitations: (citations) => {
        if (liveAssist.value) liveAssist.value.citations = citations;
      },
      onToolCall: ({ toolName }) => {
        if (!liveAssist.value) return;
        liveAssist.value.toolTrace.push({
          key: ++traceKeySeq,
          toolName,
          status: 'running',
        });
      },
      onToolResult: ({ toolName, success, resultCount, durationMs }) => {
        if (!liveAssist.value) return;
        const target = liveAssist.value.toolTrace.find((t) => t.toolName === toolName && t.status === 'running');
        if (target) {
          target.status = success ? 'done' : 'error';
          target.success = success;
          target.resultCount = resultCount;
          target.durationMs = durationMs;
        }
      },
      onDone: () => {
        optimisticUser.value = null;
        liveAssist.value = null;
        loadMessages();
      },
      onError: ({ message }) => {
        if (liveAssist.value) {
          liveAssist.value.status = 'failed';
          liveAssist.value.error = { message: message || 'Chat failed' };
        }
        optimisticUser.value = null;
        loadMessages();
      },
      onDisconnect: (reason) => {
        if (liveAssist.value) {
          liveAssist.value.status = 'interrupted';
          liveAssist.value.error = {
            message: reason || 'Connection lost before the answer completed',
          };
        }
        optimisticUser.value = null;
        // Do NOT re-POST. Show recoverable state; reload persisted messages.
        loadMessages();
      },
    },
  });

  if (!started) {
    // Duplicate send guard fired; keep local state clean.
    optimisticUser.value = null;
    liveAssist.value = null;
  }
}

function onStop() {
  chatStore.stop();
  if (liveAssist.value) {
    liveAssist.value.status = 'interrupted';
    liveAssist.value.error = { message: 'Stopped by user. Partial state may remain.' };
  }
  optimisticUser.value = null;
  loadMessages();
}

async function createConversation() {
  try {
    const c = await convoStore.create(workspaceId.value, { title: '' });
    router.push({
      name: 'workspace-conversation-chat',
      params: { workspaceId: workspaceId.value, conversationId: c.id },
    });
  } catch {
    ElMessage.error('Failed to create conversation');
  }
}

async function openConversation(id: number) {
  if (id === conversationId.value) return;
  router.push({
    name: 'workspace-conversation-chat',
    params: { workspaceId: workspaceId.value, conversationId: id },
  });
}

async function deleteConversation(id: number) {
  try {
    await ElMessageBox.confirm('Delete this conversation?', 'Delete conversation', {
      confirmButtonText: 'Delete',
      cancelButtonText: 'Cancel',
      type: 'warning',
    });
    await convoStore.remove(workspaceId.value, id);
    if (id === conversationId.value) {
      router.push({
        name: 'workspace-conversations',
        params: { workspaceId: workspaceId.value },
      });
    }
  } catch {
    /* cancelled */
  }
}

function toolFromMessage(message: ChatMessage): ToolTraceItem {
  const payload = parseToolTrace(message);
  const name = payload && typeof payload.toolName === 'string' ? payload.toolName : 'tool';
  const success = payload && typeof payload.success === 'boolean' ? payload.success : true;
  return {
    key: message.id,
    toolName: name,
    status: success ? 'done' : 'error',
    success,
    resultCount: typeof payload?.resultCount === 'number' ? payload.resultCount : undefined,
    durationMs: typeof payload?.durationMs === 'number' ? payload.durationMs : undefined,
  };
}

function scrollToBottom() {
  nextTick(() => {
    if (scrollerRef.value) scrollerRef.value.scrollTop = scrollerRef.value.scrollHeight;
  });
}

watch(
  () => [conversationId.value, workspaceId.value] as const,
  ([w, c]) => {
    chatStore.stop();
    optimisticUser.value = null;
    liveAssist.value = null;
    if (Number.isSafeInteger(w) && w > 0 && Number.isSafeInteger(c) && c > 0) {
      bootstrapConversation();
    }
  }
);

watch(
  () => convoStore.messages,
  () => scrollToBottom(),
  { deep: true }
);

watch(
  () => liveAssist.value?.content,
  () => scrollToBottom()
);

watch(mode, () => {
  if (mode.value === 'rag') loadKBs();
});

onMounted(() => {
  bootstrapConversation();
  loadKBs();
});

onBeforeUnmount(() => {
  chatStore.stop();
});
</script>

<style scoped lang="scss">
.chat-page {
  display: flex;
  height: calc(100vh - 60px);
  background: #fff;

  &__sidebar {
    width: 240px;
    border-right: 1px solid #e4e7ed;
    display: flex;
    flex-direction: column;
    flex-shrink: 0;
    background: #fafafa;
  }

  &__sidebar-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px;
  }

  &__title { font-weight: 600; color: #303133; }

  &__list {
    list-style: none;
    margin: 0;
    padding: 0;
    overflow-y: auto;
    flex: 1;
  }

  &__item {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 10px 12px;
    cursor: pointer;
    color: #303133;
    font-size: 13px;

    &:hover { background: #f0f2f5; }

    &--active { background: #ecf5ff; color: #409eff; font-weight: 600; }

    &-txt { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

    &-del { visibility: hidden; }
    &:hover &-del { visibility: visible; }
  }

  &__placeholder { padding: 16px; color: #c0c4cc; font-size: 13px; }

  &__main {
    flex: 1;
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  &__tabs {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 16px;
    border-bottom: 1px solid #e4e7ed;
  }

  &__messages {
    flex: 1;
    overflow-y: auto;
    padding: 16px;
    background: #f5f7fa;
  }

  &__empty { padding-top: 80px; }

  &__msg-row { margin-bottom: 14px; }

  &__bubble {
    max-width: 640px;
    padding: 10px 14px;
    border-radius: 10px;
    font-size: 14px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-word;

    &--user {
      margin-left: auto;
      background: #409eff;
      color: #fff;
      display: flex;
      gap: 8px;
    }

    &--user > svg {
      width: 16px;
      height: 16px;
      flex: 0 0 16px;
      margin-top: 3px;
    }

    &--assistant {
      background: #fff;
      border: 1px solid #e4e7ed;
    }
  }

  &__trace { margin-top: 6px; }

  &__interrupted {
    margin-top: 10px;
    padding: 8px 10px;
    border: 1px solid #eebe77;
    background: #fdf6ec;
    color: #e6a23c;
    border-radius: 8px;
    font-size: 13px;
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }

  &__composer {
    padding: 12px 16px;
    border-top: 1px solid #e4e7ed;
    background: #fff;
  }
}
</style>
