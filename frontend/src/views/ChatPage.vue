<template>
  <MainLayout>
    <div class="chat-page">
      <div class="chat-page__sidebar">
        <div class="chat-page__sidebar-header">
          <div><span class="chat-page__title">对话</span><small>最近会话</small></div>
          <el-button size="small" type="primary" :loading="convoStore.actionLoading" @click="createConversation">
            新建
          </el-button>
        </div>
        <LoadingSpinner v-if="convoStore.listLoading" :size="24" text="正在加载…" />
        <ul v-else-if="convoStore.items.length" class="chat-page__list">
          <li
            v-for="c in convoStore.items"
            :key="c.id"
            :class="['chat-page__item', { 'chat-page__item--active': c.id === conversationId }]"
            @click="openConversation(c.id)"
          >
            <span class="chat-page__item-txt">{{ c.title || '未命名对话' }}</span>
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
        <div v-else class="chat-page__placeholder">暂无历史对话</div>
      </div>

      <div class="chat-page__main">
        <!-- Header -->
        <div class="chat-page__tabs">
          <ModeToggle v-model="mode" :disabled="isAnswering" />
          <KnowledgeBaseSelector
            v-if="mode === 'rag'"
            ref="kbSelectorRef"
            :items="kbItems"
            :loading="kbLoading"
            :disabled="isAnswering"
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
              <span class="chat-page__empty-icon">AI</span>
              <h2>有什么可以帮你？</h2>
              <p>{{ emptyHint }}</p>
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
                <div class="chat-page__assistant-label"><span>AI</span>IntelliDesk</div>
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
                        重新加载对话
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
            :streaming="isAnswering"
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
import { createStreamingTextRenderer } from '@/lib/streamingText';
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
let backendFinished = false;

const tokenRenderer = createStreamingTextRenderer((text) => {
  if (liveAssist.value?.status === 'streaming') {
    liveAssist.value.content += text;
  }
});

const isAnswering = computed(() =>
  chatStore.isStreaming || liveAssist.value?.status === 'streaming'
);

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
  if (isAnswering.value) return true;
  if (mode.value === 'rag') {
    const selected = kbSelectorRef.value?.selected ?? [];
    return selected.length === 0;
  }
  return false;
});

const emptyHint = computed(() =>
  mode.value === 'rag'
    ? '基于当前知识库进行提问，我会结合检索结果生成回答并提供引用来源。'
    : '告诉内置 Agent 你的目标，它会按需调用工具完成任务。'
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
      ElMessage.warning('请先创建知识库，再使用 RAG 对话。');
      return;
    }
    if (selected.length === 0) {
      ElMessage.warning('请至少选择一个知识库。');
      return;
    }
    startStream(query, mode.value, selected);
  } else {
    startStream(query, 'agent');
  }
}

function startStream(query: string, streamMode: ChatMode, kbIds?: number[]) {
  tokenRenderer.cancel();
  backendFinished = false;
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
        if (liveAssist.value?.status === 'streaming') tokenRenderer.enqueue(delta);
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
        backendFinished = true;
        tokenRenderer.finish(() => {
          if (liveAssist.value?.status !== 'streaming') return;
          optimisticUser.value = null;
          liveAssist.value = null;
          backendFinished = false;
          loadMessages();
        });
      },
      onError: ({ message }) => {
        tokenRenderer.flush();
        backendFinished = false;
        if (liveAssist.value) {
          liveAssist.value.status = 'failed';
          liveAssist.value.error = { message: message || '对话失败' };
        }
        optimisticUser.value = null;
        loadMessages();
      },
      onDisconnect: (reason) => {
        tokenRenderer.flush();
        backendFinished = false;
        if (liveAssist.value) {
          liveAssist.value.status = 'interrupted';
          liveAssist.value.error = {
            message: reason || '回答完成前连接已中断',
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
  if (backendFinished) {
    tokenRenderer.flush();
    return;
  }
  chatStore.stop();
  tokenRenderer.flush();
  if (liveAssist.value) {
    liveAssist.value.status = 'interrupted';
    liveAssist.value.error = { message: '已停止生成，部分内容可能仍会保留。' };
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
    ElMessage.error('创建对话失败');
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
    await ElMessageBox.confirm('确认删除此对话吗？', '删除对话', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
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
    tokenRenderer.cancel();
    backendFinished = false;
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
  tokenRenderer.cancel();
  chatStore.stop();
});
</script>

<style scoped lang="scss">
.chat-page {
  display: flex;
  height: calc(100dvh - 64px);
  min-height: 0;
  background: var(--id-surface);

  &__sidebar {
    width: 250px;
    border-right: 1px solid var(--id-border);
    display: flex;
    flex-direction: column;
    flex-shrink: 0;
    background: #fbfbfc;
  }

  &__sidebar-header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    padding: 20px 16px 14px;
    border-bottom: 1px solid var(--id-border);
    small { display: block; margin-top: 4px; color: var(--id-text-muted); font-size: 10px; }
  }

  &__title { color: var(--id-text); font-size: 14px; font-weight: 650; }

  &__list {
    list-style: none;
    margin: 0;
    padding: 10px;
    overflow-y: auto;
    flex: 1;
  }

  &__item {
    display: flex;
    align-items: center;
    gap: 4px;
    min-height: 40px;
    margin-bottom: 2px;
    padding: 8px 10px;
    border-radius: 8px;
    cursor: pointer;
    color: var(--id-text-secondary);
    font-size: 13px;

    &:hover { background: #f1f3f6; color: var(--id-text); }

    &--active { background: var(--id-accent-soft); color: var(--id-accent); font-weight: 620; }

    &-txt { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

    &-del { visibility: hidden; }
    &:hover &-del { visibility: visible; }
  }

  &__placeholder { padding: 22px 20px; color: var(--id-text-muted); font-size: 12px; }

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
    min-height: 58px;
    padding: 10px 22px;
    border-bottom: 1px solid var(--id-border);
    background: rgba(255, 255, 255, 0.97);
  }

  &__messages {
    flex: 1;
    overflow-y: auto;
    padding: 28px clamp(20px, 5vw, 64px);
    background: #f8f9fb;
    scroll-behavior: smooth;
  }

  &__empty {
    display: flex;
    max-width: 580px;
    margin: 0 auto;
    padding-top: min(16vh, 130px);
    flex-direction: column;
    align-items: center;
    text-align: center;
    &-icon { display: grid; width: 46px; height: 46px; margin-bottom: 20px; place-items: center; border: 1px solid #d4def8; border-radius: 14px; background: #fff; color: var(--id-accent); box-shadow: var(--id-shadow-sm); font-size: 12px; font-weight: 750; }
    h2 { margin: 0; color: var(--id-text); font-size: 24px; font-weight: 650; letter-spacing: -0.03em; }
    p { margin: 12px 0 0; color: var(--id-text-muted); font-size: 14px; line-height: 1.7; }
  }

  &__msg-row { width: min(100%, 880px); margin: 0 auto 22px; }

  &__bubble {
    max-width: 780px;
    font-size: 14px;
    line-height: 1.75;
    white-space: pre-wrap;
    word-break: break-word;

    &--user {
      width: fit-content;
      max-width: min(680px, 88%);
      margin-left: auto;
      padding: 11px 15px;
      border-radius: 13px 13px 4px 13px;
      background: var(--id-accent);
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
      padding: 2px 0;
      color: #273244;
    }
  }

  &__assistant-label {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 10px;
    color: var(--id-text);
    font-size: 12px;
    font-weight: 650;
    span { display: grid; width: 25px; height: 25px; place-items: center; border: 1px solid #d8e2fa; border-radius: 7px; background: #fff; color: var(--id-accent); font-size: 9px; }
  }

  &__tool-item { max-width: 780px; }
  &__trace { margin-top: 6px; }

  &__interrupted {
    margin-top: 10px;
    padding: 8px 10px;
    border: 1px solid #ead29b;
    background: #fffbeb;
    color: #9a6700;
    border-radius: 8px;
    font-size: 13px;
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }

  &__composer {
    padding: 14px clamp(20px, 5vw, 64px) 18px;
    border-top: 1px solid var(--id-border);
    background: rgba(255, 255, 255, 0.97);
    :deep(.chat-composer) { width: min(100%, 880px); margin: 0 auto; }
  }
}

@media (max-width: 900px) {
  .chat-page__sidebar { width: 190px; }
}

@media (max-width: 720px) {
  .chat-page {
    flex-direction: column;

    &__sidebar {
      display: flex;
      width: 100%;
      max-height: 116px;
      border-right: 0;
      border-bottom: 1px solid var(--id-border);
    }

    &__sidebar-header {
      align-items: center;
      padding: 8px 12px 6px;
      border-bottom: 0;
      small { display: none; }
    }

    &__list {
      display: flex;
      flex: 0 0 auto;
      gap: 5px;
      padding: 4px 10px 8px;
      overflow-x: auto;
    }

    &__item { min-width: 150px; margin: 0; }
    &__placeholder { padding: 4px 14px 10px; }
    &__main { min-height: 0; }
    &__tabs { padding-inline: 14px; }
    &__messages { padding: 22px 16px; }
    &__composer { padding: 12px 14px 14px; }
  }
}
</style>
