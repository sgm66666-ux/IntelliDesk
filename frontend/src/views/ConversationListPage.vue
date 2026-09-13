<template>
  <MainLayout>
    <div class="page-container">
      <div class="page-header">
        <div>
          <h1>智能对话</h1>
          <p>基于知识库发起可追溯问答，或使用内置 Agent 完成任务。</p>
        </div>
        <el-button type="primary" :loading="store.actionLoading" @click="createNew">
          新建对话
        </el-button>
      </div>

      <LoadingSpinner v-if="store.listLoading" text="正在加载对话…" />
      <ErrorMessage
        v-else-if="store.listError"
        :message="store.listError.message"
        :retry="true"
        @retry="load"
      />
      <EmptyState
        v-else-if="store.items.length === 0"
        description="还没有对话，创建一个开始提问吧"
        action-label="新建对话"
        @action="createNew"
      />
      <el-table v-else :data="store.items" @row-click="openChat" class="conversation-table">
        <el-table-column prop="title" label="对话标题" min-width="260" />
        <el-table-column label="最近更新" width="180">
          <template #default="scope">{{ formatDate(scope.row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="scope">{{ formatDate(scope.row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="scope">
            <el-button text type="primary" @click.stop="openRename(scope.row)">重命名</el-button>
            <el-button text type="danger" @click.stop="confirmDelete(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </MainLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import MainLayout from '@/layouts/MainLayout.vue';
import LoadingSpinner from '@/components/common/LoadingSpinner.vue';
import EmptyState from '@/components/common/EmptyState.vue';
import ErrorMessage from '@/components/common/ErrorMessage.vue';
import { useConversationStore } from '@/stores/conversation';
import type { Conversation } from '@/types/conversation';

const route = useRoute();
const router = useRouter();
const store = useConversationStore();
const workspaceId = computed(() => Number(route.params.workspaceId));

function validWorkspace() {
  return Number.isSafeInteger(workspaceId.value) && workspaceId.value > 0;
}

function load() {
  if (validWorkspace()) store.fetchList(workspaceId.value).catch(() => undefined);
}

async function createNew() {
  try {
    const conversation = await store.create(workspaceId.value, { title: '' });
    router.push({
      name: 'workspace-conversation-chat',
      params: { workspaceId: workspaceId.value, conversationId: conversation.id },
    });
  } catch {
    ElMessage.error('创建对话失败');
  }
}

function openChat(row: Conversation) {
  router.push({
    name: 'workspace-conversation-chat',
    params: { workspaceId: workspaceId.value, conversationId: row.id },
  });
}

async function openRename(row: Conversation) {
  try {
    const { value } = await ElMessageBox.prompt('输入新的对话标题', '重命名对话', {
      inputValue: row.title,
      inputPattern: /^.{1,256}$/,
      inputErrorMessage: '标题长度需为 1–256 个字符',
    });
    await store.rename(workspaceId.value, row.id, value);
    ElMessage.success('对话已重命名');
  } catch {
    /* cancelled */
  }
}

async function confirmDelete(row: Conversation) {
  try {
    await ElMessageBox.confirm(`确认删除“${row.title}”吗？其中的消息也会被删除。`, '删除对话', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    });
    await store.remove(workspaceId.value, row.id);
    ElMessage.success('对话已删除');
  } catch {
    /* cancelled */
  }
}

function formatDate(value?: string) {
  if (!value) return '';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? value : d.toLocaleString();
}

onMounted(load);
watch(workspaceId, () => load());
</script>

<style scoped lang="scss">
.conversation-table {
  cursor: pointer;
}
</style>
