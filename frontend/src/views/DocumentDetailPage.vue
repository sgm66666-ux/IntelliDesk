<template>
  <MainLayout>
    <div class="page-container">
      <div class="page-header">
        <div>
          <el-button text @click="goBack">Back to knowledge base</el-button>
          <h1>{{ store.currentDocument?.fileName || 'Document' }}</h1>
        </div>
        <div class="actions" v-if="store.currentDocument">
          <el-button v-if="store.currentDocument.status === 'FAILED'" type="primary" :loading="store.actionLoading" @click="retry">Retry</el-button>
          <el-button type="danger" plain :loading="store.actionLoading" @click="confirmDelete">Delete</el-button>
        </div>
      </div>

      <LoadingSpinner v-if="store.detailLoading" text="Loading document..." />
      <ErrorMessage v-else-if="store.detailError" :message="store.detailError.message" :retry="true" @retry="load" />
      <template v-else-if="store.currentDocument">
        <ErrorMessage
          v-if="store.actionError"
          :message="store.actionError.message"
          :code="store.actionError.code"
          :trace-id="store.actionError.traceId"
          :retry="false"
        />
        <el-card shadow="never" class="metadata">
          <div class="status-line"><DocumentStatusBadge :status="store.currentDocument.status" /></div>
          <dl>
            <dt>Content type</dt><dd>{{ store.currentDocument.contentType || '—' }}</dd>
            <dt>File size</dt><dd>{{ formatSize(store.currentDocument.fileSize) }}</dd>
            <dt>Created</dt><dd>{{ store.currentDocument.createdAt || '—' }}</dd>
            <dt>Chunk strategy</dt><dd>{{ store.currentDocument.chunkStrategy || '—' }}</dd>
          </dl>
          <el-alert v-if="store.currentDocument.failureMessage" type="error" :title="store.currentDocument.failureMessage" :closable="false" show-icon />
          <el-collapse v-if="store.currentDocument.parserMetadata">
            <el-collapse-item title="Parser metadata">
              <pre>{{ stringify(store.currentDocument.parserMetadata) }}</pre>
            </el-collapse-item>
          </el-collapse>
        </el-card>
        <ChunkViewer
          v-if="store.currentDocument.status === 'COMPLETED'"
          :chunks="store.chunks"
          :loading="store.chunksLoading"
          :error="chunkError"
          :page="store.chunkPage"
          :size="store.chunkSize"
          :total="store.chunkTotal"
          @refresh="loadChunks"
          @page-change="loadChunks"
        />
        <el-alert v-else type="info" title="Chunks are available after document parsing completes." :closable="false" />
      </template>
    </div>
  </MainLayout>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import MainLayout from '@/layouts/MainLayout.vue';
import LoadingSpinner from '@/components/common/LoadingSpinner.vue';
import ErrorMessage from '@/components/common/ErrorMessage.vue';
import DocumentStatusBadge from '@/components/DocumentStatusBadge.vue';
import ChunkViewer from '@/components/ChunkViewer.vue';
import { useDocumentStore } from '@/stores/document';

const route = useRoute();
const router = useRouter();
const store = useDocumentStore();
const workspaceId = computed(() => Number(route.params.workspaceId));
const knowledgeBaseId = computed(() => Number(route.params.knowledgeBaseId));
const documentId = computed(() => Number(route.params.documentId));
const chunkError = computed(() => store.chunksError?.code === 4008 ? 'Chunks are currently unavailable.' : store.chunksError?.message);

async function load() {
  if (workspaceId.value <= 0 || knowledgeBaseId.value <= 0 || documentId.value <= 0) return;
  store.stopPolling();
  const detail = await store.fetchDetail(workspaceId.value, knowledgeBaseId.value, documentId.value);
  if (detail?.status === 'COMPLETED') await store.fetchChunks(workspaceId.value, knowledgeBaseId.value, documentId.value);
  if (detail?.status === 'PENDING' || detail?.status === 'PROCESSING') {
    store.startPolling(workspaceId.value, knowledgeBaseId.value, documentId.value);
  }
}

function loadChunks(page = 1) {
  return store.fetchChunks(workspaceId.value, knowledgeBaseId.value, documentId.value, page);
}

async function retry() {
  try {
    await store.retry(workspaceId.value, knowledgeBaseId.value, documentId.value);
    await store.fetchDetail(workspaceId.value, knowledgeBaseId.value, documentId.value);
    store.startPolling(workspaceId.value, knowledgeBaseId.value, documentId.value);
  } catch {
    void 0;
  }
}

async function confirmDelete() {
  try {
    await ElMessageBox.confirm('Delete this document?', 'Delete document', {
      confirmButtonText: 'Delete', cancelButtonText: 'Cancel', type: 'warning',
    });
  } catch {
    return;
  }

  try {
    await store.remove(workspaceId.value, knowledgeBaseId.value, documentId.value);
    goBack();
  } catch {
    void 0;
  }
}

function goBack() {
  router.push({ name: 'knowledge-base-detail', params: { workspaceId: workspaceId.value, knowledgeBaseId: knowledgeBaseId.value } });
}

function formatSize(size?: number | null) {
  if (size === null || size === undefined) return '—';
  if (size < 1024) return `${size} B`;
  return `${(size / 1024 / 1024).toFixed(2)} MB`;
}

function stringify(value: Record<string, unknown>) {
  return JSON.stringify(value, null, 2);
}

onMounted(load);
onBeforeUnmount(() => store.clearDetail());
watch([workspaceId, knowledgeBaseId, documentId], load);
</script>

<style scoped lang="scss">
.page-header, .actions, .status-line { display: flex; align-items: center; gap: 12px; }
.page-header { justify-content: space-between; margin-bottom: 24px; }
.page-header h1 { margin: 8px 0 0; }
.metadata { margin-bottom: 24px; }
dl { display: grid; grid-template-columns: 160px 1fr; gap: 8px 16px; margin: 16px 0; }
dt { color: #606266; }
dd { margin: 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
