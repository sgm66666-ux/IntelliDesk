<template>
  <MainLayout>
    <div class="page-container">
      <div class="page-header">
        <div>
          <h1>Conversations</h1>
          <p>Chat with your knowledge bases or the built-in agent.</p>
        </div>
        <el-button type="primary" :loading="store.actionLoading" @click="createNew">
          New Conversation
        </el-button>
      </div>

      <LoadingSpinner v-if="store.listLoading" text="Loading conversations…" />
      <ErrorMessage
        v-else-if="store.listError"
        :message="store.listError.message"
        :retry="true"
        @retry="load"
      />
      <EmptyState
        v-else-if="store.items.length === 0"
        description="No conversations yet. Start a new one to chat."
        action-label="New Conversation"
        @action="createNew"
      />
      <el-table v-else :data="store.items" @row-click="openChat" class="conversation-table">
        <el-table-column prop="title" label="Title" min-width="260" />
        <el-table-column label="Updated" width="180">
          <template #default="scope">{{ formatDate(scope.row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="Created" width="180">
          <template #default="scope">{{ formatDate(scope.row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="Actions" width="150" fixed="right">
          <template #default="scope">
            <el-button text type="primary" @click.stop="openRename(scope.row)">Rename</el-button>
            <el-button text type="danger" @click.stop="confirmDelete(scope.row)">Delete</el-button>
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
    ElMessage.error('Failed to create conversation');
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
    const { value } = await ElMessageBox.prompt('Conversation title', 'Rename conversation', {
      inputValue: row.title,
      inputPattern: /^.{1,256}$/,
      inputErrorMessage: 'Title must be 1-256 characters',
    });
    await store.rename(workspaceId.value, row.id, value);
    ElMessage.success('Conversation renamed');
  } catch {
    /* cancelled */
  }
}

async function confirmDelete(row: Conversation) {
  try {
    await ElMessageBox.confirm(`Delete "${row.title}"? Messages will be removed.`, 'Delete conversation', {
      confirmButtonText: 'Delete',
      cancelButtonText: 'Cancel',
      type: 'warning',
    });
    await store.remove(workspaceId.value, row.id);
    ElMessage.success('Conversation deleted');
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
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.page-header p {
  margin: 4px 0 0;
  color: #606266;
}

.conversation-table {
  cursor: pointer;
}
</style>