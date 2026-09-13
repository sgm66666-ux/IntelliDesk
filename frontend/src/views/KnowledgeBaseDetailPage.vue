<template>
  <MainLayout>
    <div class="page-container">
      <div class="page-header">
        <div>
          <el-button text @click="goBack">← 返回知识库</el-button>
          <h1>{{ kbStore.currentKnowledgeBase?.name || '知识库' }}</h1>
        </div>
        <div class="actions">
          <el-button @click="openEdit">编辑</el-button>
          <el-button type="danger" plain @click="confirmDelete">删除</el-button>
        </div>
      </div>

      <LoadingSpinner v-if="kbStore.detailLoading" text="正在加载知识库…" />
      <ErrorMessage v-else-if="kbStore.detailError" :message="kbStore.detailError.message" :retry="true" @retry="load" />
      <template v-else-if="kbStore.currentKnowledgeBase">
        <el-card class="metadata" shadow="never">
          <p>{{ kbStore.currentKnowledgeBase.description || '暂无描述' }}</p>
          <span>{{ kbStore.currentKnowledgeBase.chunkStrategy }} · Chunk {{ kbStore.currentKnowledgeBase.chunkSize }} · 重叠 {{ kbStore.currentKnowledgeBase.chunkOverlap }}</span>
        </el-card>

        <section class="documents">
          <div class="section-header">
            <div><h2>文档</h2><p>上传并管理用于检索的知识源。</p></div>
            <div class="section-actions">
              <el-select v-model="docStore.statusFilter" clearable placeholder="全部状态" @change="filterDocuments">
                <el-option v-for="status in statuses" :key="status" :label="documentStatusLabel(status)" :value="status" />
              </el-select>
              <el-button @click="loadDocuments">刷新</el-button>
            </div>
          </div>
          <DocumentUpload :loading="docStore.uploadLoading" :progress="docStore.uploadProgress" @upload="upload" />
          <el-alert v-if="docStore.uploadAccepted" type="success" :closable="false" show-icon class="accepted-message">
            {{ docStore.uploadAccepted.fileName }} 已接收，正在等待处理。可进入详情查看进度。
          </el-alert>
          <ErrorMessage v-if="docStore.actionError" :message="docStore.actionError.message" :retry="false" />
          <LoadingSpinner v-if="docStore.listLoading" text="正在加载文档…" />
          <ErrorMessage v-else-if="docStore.listError" :message="docStore.listError.message" :retry="true" @retry="loadDocuments" />
          <EmptyState v-else-if="docStore.items.length === 0" description="暂无文档" />
          <div v-else>
            <el-table :data="docStore.items">
              <el-table-column prop="fileName" label="文件" min-width="260" />
              <el-table-column prop="contentType" label="类型" min-width="160" />
              <el-table-column label="状态" min-width="150">
                <template #default="scope"><DocumentStatusBadge :status="scope.row.status" /></template>
              </el-table-column>
              <el-table-column prop="createdAt" label="创建时间" min-width="180" />
              <el-table-column label="操作" width="120">
                <template #default="scope">
                  <el-button text type="primary" @click="openDocument(scope.row.id)">查看详情</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-pagination
              class="pagination"
              layout="total, prev, pager, next"
              :current-page="docStore.page"
              :page-size="docStore.size"
              :total="docStore.total"
              @current-change="(value: number) => docStore.fetchList(workspaceId, knowledgeBaseId, value, docStore.statusFilter)"
            />
          </div>
        </section>
      </template>

      <KnowledgeBaseFormDialog
        v-model="showEdit"
        title="编辑知识库"
        :initial-value="editValue"
        :loading="kbStore.actionLoading"
        :submit-error="kbStore.actionError?.message"
        @submit="update"
      />
    </div>
  </MainLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import MainLayout from '@/layouts/MainLayout.vue';
import LoadingSpinner from '@/components/common/LoadingSpinner.vue';
import EmptyState from '@/components/common/EmptyState.vue';
import ErrorMessage from '@/components/common/ErrorMessage.vue';
import KnowledgeBaseFormDialog from '@/components/KnowledgeBaseFormDialog.vue';
import DocumentUpload from '@/components/DocumentUpload.vue';
import DocumentStatusBadge from '@/components/DocumentStatusBadge.vue';
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase';
import { useDocumentStore } from '@/stores/document';
import type { KnowledgeBaseRequest } from '@/types/knowledgeBase';
import type { DocumentStatus } from '@/types/document';
import { documentStatusLabel } from '@/lib/display';

const route = useRoute();
const router = useRouter();
const kbStore = useKnowledgeBaseStore();
const docStore = useDocumentStore();
const workspaceId = computed(() => Number(route.params.workspaceId));
const knowledgeBaseId = computed(() => Number(route.params.knowledgeBaseId));
const showEdit = ref(false);
const editValue = ref<KnowledgeBaseRequest | null>(null);
const statuses: DocumentStatus[] = ['UPLOADING', 'PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DELETING'];

function validIds() {
  return workspaceId.value > 0 && knowledgeBaseId.value > 0;
}

async function load() {
  if (!validIds()) return;
  await kbStore.fetchDetail(workspaceId.value, knowledgeBaseId.value);
  await loadDocuments();
}

function loadDocuments() {
  return docStore.fetchList(workspaceId.value, knowledgeBaseId.value, 1, docStore.statusFilter);
}

function filterDocuments() {
  loadDocuments();
}

function openEdit() {
  const item = kbStore.currentKnowledgeBase;
  if (!item) return;
  editValue.value = {
    name: item.name,
    description: item.description ?? '',
    chunkStrategy: item.chunkStrategy,
    chunkSize: item.chunkSize,
    chunkOverlap: item.chunkOverlap,
  };
  showEdit.value = true;
}

async function update(value: KnowledgeBaseRequest) {
  try {
    await kbStore.update(workspaceId.value, knowledgeBaseId.value, value);
    showEdit.value = false;
  } catch {}
}

async function confirmDelete() {
  try {
    await ElMessageBox.confirm('删除知识库前必须先删除其中的全部文档。', '删除知识库', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
    });
    await kbStore.remove(workspaceId.value, knowledgeBaseId.value);
    goBack();
  } catch {}
}

async function upload(file: File) {
  try {
    await docStore.upload(workspaceId.value, knowledgeBaseId.value, file);
  } catch {}
}

function openDocument(documentId: number) {
  router.push({ name: 'document-detail', params: { workspaceId: workspaceId.value, knowledgeBaseId: knowledgeBaseId.value, documentId } });
}

function goBack() {
  router.push({ name: 'workspace-knowledge-bases', params: { workspaceId: workspaceId.value } });
}

onMounted(load);
watch([workspaceId, knowledgeBaseId], () => load());
</script>

<style scoped lang="scss">
.page-header, .section-header, .actions, .section-actions { display: flex; align-items: center; gap: 12px; }
.page-header, .section-header { justify-content: space-between; }
.page-header h1 { margin: 8px 0 0; }
.metadata { margin-bottom: 28px; background: var(--id-surface-subtle); }
.metadata p { margin: 0 0 10px; color: var(--id-text-secondary); line-height: 1.65; }
.metadata span { color: var(--id-text-muted); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; }
.documents { margin-top: 30px; }
.section-header { margin-bottom: 16px; }
.section-header h2 { margin: 0; color: var(--id-text); font-size: 18px; }
.section-header p { margin: 5px 0 0; color: var(--id-text-muted); font-size: 13px; }
.accepted-message { margin: 16px 0; }
.pagination { justify-content: flex-end; margin-top: 16px; }
@media (max-width: 720px) { .section-header { align-items: flex-start; flex-direction: column; } }
</style>
