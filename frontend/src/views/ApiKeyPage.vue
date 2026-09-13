<template>
  <MainLayout>
    <div class="page-container">
      <div class="page-header">
        <div>
          <h1>API Key 管理</h1>
          <p>创建和管理用于调用 IntelliDesk API 的访问凭证。</p>
        </div>
        <el-button type="primary" :data-testid="`${dataId}-new`" @click="openCreate">
          创建 API Key
        </el-button>
      </div>

      <LoadingSpinner v-if="store.listLoading" text="正在加载 API Key…" />
      <ErrorMessage
        v-else-if="store.listError"
        :message="store.listError.message"
        :code="store.listError.code"
        :trace-id="store.listError.traceId"
        :retry="true"
        @retry="load"
      />
      <EmptyState
        v-else-if="store.items.length === 0"
        description="暂无 API Key，可创建一个用于程序化访问"
        action-label="创建 API Key"
        @action="openCreate"
      />
      <ApiKeyTable
        v-else
        :items="store.items"
        :revoking-id="store.revokingId"
        :data-id="dataId"
        @revoke="onRevoke"
      />

      <ApiKeyCreateDialog
        v-model="createVisible"
        :creating="store.creating"
        :error="store.actionError"
        :secret="store.transientSecret"
        :data-id="dataId"
        @create="onCreate"
        @close="onCreateDialogClose"
      />
    </div>
  </MainLayout>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import MainLayout from '@/layouts/MainLayout.vue';
import LoadingSpinner from '@/components/common/LoadingSpinner.vue';
import EmptyState from '@/components/common/EmptyState.vue';
import ErrorMessage from '@/components/common/ErrorMessage.vue';
import ApiKeyTable from '@/components/api-key/ApiKeyTable.vue';
import ApiKeyCreateDialog from '@/components/api-key/ApiKeyCreateDialog.vue';
import { useApiKeyStore } from '@/stores/apiKey';
import { useAuthStore } from '@/stores/auth';
import type { ApiKey, CreateApiKeyRequest } from '@/types/apiKey';

const route = useRoute();
const store = useApiKeyStore();
const auth = useAuthStore();
const createVisible = ref(false);
const dataId = 'api-key';

const workspaceId = computed(() => Number(route.params.workspaceId));

function validWorkspace() {
  return Number.isSafeInteger(workspaceId.value) && workspaceId.value > 0;
}

function load() {
  if (validWorkspace()) store.fetchList(workspaceId.value).catch(() => undefined);
}

function openCreate() {
  createVisible.value = true;
}

async function onCreate(payload: CreateApiKeyRequest) {
  try {
    await store.create(workspaceId.value, payload);
    // Keep the dialog open so the one-time secret is displayed; do not toast it.
  } catch {
    // error surfaced via store.actionError in dialog
  }
}

/** On dialog close we must drop the one-time secret immediately. */
function onCreateDialogClose() {
  store.clearSecret();
}

async function onRevoke(key: ApiKey) {
  try {
    await store.revoke(workspaceId.value, key.id);
    ElMessage.success('API Key 已撤销');
  } catch {
    ElMessage.error('撤销 API Key 失败');
  }
}

// Workspace switch: drop the transient secret (and out-of-date list) before loading B.
watch(
  workspaceId,
  () => {
    store.clearSecret();
    createVisible.value = false;
    load();
  },
  { flush: 'pre' }
);

// Logout: never let a pending one-time secret survive a logout/re-login.
watch(
  () => auth.authenticated,
  (authed) => {
    if (!authed) store.clearSecret();
  }
);

onMounted(load);
onBeforeUnmount(() => {
  store.clearSecret();
});
</script>

<style scoped lang="scss">
.page-container { max-width: 1320px; }
</style>
