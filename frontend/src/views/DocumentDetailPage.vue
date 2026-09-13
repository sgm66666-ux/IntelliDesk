<template>
  <MainLayout>
    <div class="page-container">
      <div class="page-header">
        <div>
          <el-button text @click="goBack">← 返回知识库</el-button>
          <h1>{{ store.currentDocument?.fileName || '文档' }}</h1>
        </div>
        <div class="actions" v-if="store.currentDocument">
          <el-button v-if="store.currentDocument.status === 'FAILED'" type="primary" :loading="store.actionLoading" @click="retry">重试</el-button>
          <el-button type="danger" plain :loading="store.actionLoading" @click="confirmDelete">删除</el-button>
        </div>
      </div>

      <LoadingSpinner v-if="store.detailLoading" text="正在加载文档…" />
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
            <div><dt>内容类型</dt><dd>{{ store.currentDocument.contentType || '—' }}</dd></div>
            <div><dt>文件大小</dt><dd>{{ formatSize(store.currentDocument.fileSize) }}</dd></div>
            <div><dt>创建时间</dt><dd>{{ store.currentDocument.createdAt || '—' }}</dd></div>
            <div><dt>分块策略</dt><dd>{{ store.currentDocument.chunkStrategy || '—' }}</dd></div>
          </dl>
          <el-alert v-if="store.currentDocument.failureMessage" type="error" :title="store.currentDocument.failureMessage" :closable="false" show-icon />
          <el-collapse v-if="store.currentDocument.parserMetadata">
            <el-collapse-item title="解析器元数据">
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
        <el-alert v-else type="info" title="文档解析完成后即可查看 Chunk。" :closable="false" />
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
const chunkError = computed(() => store.chunksError?.code === 4008 ? 'Chunk 当前不可用。' : store.chunksError?.message);

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
    await ElMessageBox.confirm('确认删除此文档吗？', '删除文档', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
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
.page-header { justify-content: space-between; }
.page-header h1 { margin: 8px 0 0; }
.metadata { margin-bottom: 28px; }
.status-line { padding-bottom: 16px; border-bottom: 1px solid var(--id-border); }
dl { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 1px; margin: 0 0 18px; background: var(--id-border); }
dl > div { min-width: 0; padding: 18px; background: #fff; }
dt { margin-bottom: 7px; color: var(--id-text-muted); font-size: 11px; font-weight: 650; }
dd { margin: 0; overflow: hidden; color: var(--id-text); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; color: var(--id-text-secondary); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; }
@media (max-width: 900px) { dl { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { dl { grid-template-columns: 1fr; } }
</style>
