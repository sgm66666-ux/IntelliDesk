<template>
  <MainLayout>
    <div class="page-container">
      <div class="page-header">
        <div>
          <h1>API Keys</h1>
          <p>Manage read-only API keys for this workspace.</p>
        </div>
        <el-button type="primary" :data-testid="`${dataId}-new`" @click="openCreate">
          New API Key
        </el-button>
      </div>

      <LoadingSpinner v-if="store.listLoading" text="Loading API keys…" />
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
        description="No API keys yet. Create one to authenticate programmatic access."
        action-label="New API Key"
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
    ElMessage.success('API key revoked');
  } catch {
    ElMessage.error('Failed to revoke API key');
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
</style>