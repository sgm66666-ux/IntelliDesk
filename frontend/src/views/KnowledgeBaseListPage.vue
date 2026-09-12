<template>
  <MainLayout>
    <div class="page-container">
      <div class="page-header">
        <div>
          <h1>Knowledge Bases</h1>
          <p>Manage document collections for this workspace.</p>
        </div>
        <el-button type="primary" @click="showCreate = true">Create knowledge base</el-button>
      </div>

      <div class="toolbar">
        <el-input v-model="searchText" placeholder="Search knowledge bases" clearable @keyup.enter="search" @clear="search" />
        <el-button @click="search">Search</el-button>
      </div>

      <LoadingSpinner v-if="store.listLoading" text="Loading knowledge bases..." />
      <ErrorMessage v-else-if="store.listError" :message="store.listError.message" :retry="true" @retry="load" />
      <EmptyState v-else-if="store.items.length === 0" description="No knowledge bases found" action-label="Create knowledge base" @action="showCreate = true" />
      <div v-else>
        <el-table :data="store.items" @row-click="openDetail">
          <el-table-column prop="name" label="Name" min-width="220" />
          <el-table-column prop="description" label="Description" min-width="280" />
          <el-table-column label="Chunking" min-width="180">
            <template #default="scope">{{ scope.row.chunkStrategy }} · {{ scope.row.chunkSize }}/{{ scope.row.chunkOverlap }}</template>
          </el-table-column>
          <el-table-column label="Actions" width="180" fixed="right">
            <template #default="scope">
              <el-button text type="primary" @click.stop="openEdit(scope.row)">Edit</el-button>
              <el-button text type="danger" @click.stop="confirmDelete(scope.row.id, scope.row.name)">Delete</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          class="pagination"
          layout="total, prev, pager, next"
          :current-page="store.page"
          :page-size="store.size"
          :total="store.total"
          @current-change="(value: number) => load(value)"
        />
      </div>

      <KnowledgeBaseFormDialog
        v-model="showCreate"
        title="Create Knowledge Base"
        :loading="store.actionLoading"
        :submit-error="store.actionError?.message"
        @submit="create"
      />
      <KnowledgeBaseFormDialog
        v-model="showEdit"
        title="Edit Knowledge Base"
        :initial-value="editing"
        :loading="store.actionLoading"
        :submit-error="store.actionError?.message"
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
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase';
import type { KnowledgeBase, KnowledgeBaseRequest } from '@/types/knowledgeBase';

const route = useRoute();
const router = useRouter();
const store = useKnowledgeBaseStore();
const showCreate = ref(false);
const showEdit = ref(false);
const editing = ref<KnowledgeBaseRequest | null>(null);
const editingId = ref<number | null>(null);
const searchText = ref('');
const workspaceId = computed(() => Number(route.params.workspaceId));

function validWorkspace() {
  return Number.isSafeInteger(workspaceId.value) && workspaceId.value > 0;
}

function load(nextPage = 1) {
  if (validWorkspace()) store.fetchList(workspaceId.value, nextPage, searchText.value.trim());
}

function search() {
  load(1);
}

function openDetail(row: KnowledgeBase) {
  router.push({ name: 'knowledge-base-detail', params: { workspaceId: workspaceId.value, knowledgeBaseId: row.id } });
}

function openEdit(item: KnowledgeBase) {
  editingId.value = item.id;
  editing.value = {
    name: item.name,
    description: item.description ?? '',
    chunkStrategy: item.chunkStrategy,
    chunkSize: item.chunkSize,
    chunkOverlap: item.chunkOverlap,
  };
  showEdit.value = true;
}

async function create(value: KnowledgeBaseRequest) {
  try {
    const created = await store.create(workspaceId.value, value);
    showCreate.value = false;
    if (created.id) openDetail(created);
  } catch {}
}

async function update(value: KnowledgeBaseRequest) {
  if (!editingId.value) return;
  try {
    await store.update(workspaceId.value, editingId.value, value);
    showEdit.value = false;
  } catch {}
}

async function confirmDelete(id: number, name: string) {
  try {
    await ElMessageBox.confirm(`Delete "${name}"?`, 'Delete knowledge base', {
      confirmButtonText: 'Delete', cancelButtonText: 'Cancel', type: 'warning',
    });
    await store.remove(workspaceId.value, id);
  } catch {}
}

onMounted(load);
watch(workspaceId, () => load());
</script>

<style scoped lang="scss">
.page-header, .toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.page-header { margin-bottom: 24px; }
.page-header p { margin: 4px 0 0; color: #606266; }
.toolbar { max-width: 520px; margin-bottom: 16px; }
.pagination { justify-content: flex-end; margin-top: 16px; }
</style>
