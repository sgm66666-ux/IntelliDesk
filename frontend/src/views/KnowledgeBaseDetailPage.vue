<template>
  <MainLayout>
    <div class="page-container">
      <div class="page-header">
        <div>
          <el-button text @click="goBack">Back</el-button>
          <h1>{{ kbStore.currentKnowledgeBase?.name || 'Knowledge Base' }}</h1>
        </div>
        <div class="actions">
          <el-button @click="openEdit">Edit</el-button>
          <el-button type="danger" plain @click="confirmDelete">Delete</el-button>
        </div>
      </div>

      <LoadingSpinner v-if="kbStore.detailLoading" text="Loading knowledge base..." />
      <ErrorMessage v-else-if="kbStore.detailError" :message="kbStore.detailError.message" :retry="true" @retry="load" />
      <template v-else-if="kbStore.currentKnowledgeBase">
        <el-card class="metadata" shadow="never">
          <p>{{ kbStore.currentKnowledgeBase.description || 'No description' }}</p>
          <span>{{ kbStore.currentKnowledgeBase.chunkStrategy }} · size {{ kbStore.currentKnowledgeBase.chunkSize }} · overlap {{ kbStore.currentKnowledgeBase.chunkOverlap }}</span>
        </el-card>

        <section class="documents">
          <div class="section-header">
            <h2>Documents</h2>
            <div class="section-actions">
              <el-select v-model="docStore.statusFilter" clearable placeholder="All statuses" @change="filterDocuments">
                <el-option v-for="status in statuses" :key="status" :label="status" :value="status" />
              </el-select>
              <el-button @click="loadDocuments">Refresh</el-button>
            </div>
          </div>
          <DocumentUpload :loading="docStore.uploadLoading" :progress="docStore.uploadProgress" @upload="upload" />
          <el-alert v-if="docStore.uploadAccepted" type="success" :closable="false" show-icon class="accepted-message">
            {{ docStore.uploadAccepted.fileName }} accepted / waiting for processing. Open its detail to monitor processing.
          </el-alert>
          <ErrorMessage v-if="docStore.actionError" :message="docStore.actionError.message" :retry="false" />
          <LoadingSpinner v-if="docStore.listLoading" text="Loading documents..." />
          <ErrorMessage v-else-if="docStore.listError" :message="docStore.listError.message" :retry="true" @retry="loadDocuments" />
          <EmptyState v-else-if="docStore.items.length === 0" description="No documents found" />
          <div v-else>
            <el-table :data="docStore.items">
              <el-table-column prop="fileName" label="File" min-width="260" />
              <el-table-column prop="contentType" label="Type" min-width="160" />
              <el-table-column label="Status" min-width="230">
                <template #default="scope"><DocumentStatusBadge :status="scope.row.status" /></template>
              </el-table-column>
              <el-table-column prop="createdAt" label="Created" min-width="180" />
              <el-table-column label="Action" width="120">
                <template #default="scope">
                  <el-button text type="primary" @click="openDocument(scope.row.id)">Details</el-button>
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
        title="Edit Knowledge Base"
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
    await ElMessageBox.confirm('All documents must be deleted before deleting this knowledge base.', 'Delete knowledge base', {
      confirmButtonText: 'Delete', cancelButtonText: 'Cancel', type: 'warning',
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
.page-header { margin-bottom: 24px; }
.page-header h1 { margin: 8px 0 0; }
.metadata { margin-bottom: 24px; }
.metadata p { margin-top: 0; }
.metadata span { color: #606266; }
.documents { margin-top: 24px; }
.accepted-message { margin: 16px 0; }
.pagination { justify-content: flex-end; margin-top: 16px; }
</style>
