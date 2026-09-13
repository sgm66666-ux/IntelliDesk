<template>
  <MainLayout>
    <div class="page-container">
      <div class="page-header">
        <div>
          <h1>知识库</h1>
          <p>管理用于 RAG 检索的文档集合与分块策略。</p>
        </div>
        <el-button type="primary" @click="showCreate = true">创建知识库</el-button>
      </div>

      <div class="toolbar">
        <el-input v-model="searchText" placeholder="搜索知识库名称" clearable @keyup.enter="search" @clear="search" />
        <el-button @click="search">搜索</el-button>
      </div>

      <LoadingSpinner v-if="store.listLoading" text="正在加载知识库…" />
      <ErrorMessage v-else-if="store.listError" :message="store.listError.message" :retry="true" @retry="load" />
      <EmptyState v-else-if="store.items.length === 0" description="未找到知识库" action-label="创建知识库" @action="showCreate = true" />
      <div v-else>
        <el-table :data="store.items" @row-click="openDetail">
          <el-table-column label="名称" min-width="220">
            <template #default="scope"><strong class="kb-name">{{ scope.row.name }}</strong></template>
          </el-table-column>
          <el-table-column label="描述" min-width="280">
            <template #default="scope"><span class="kb-description">{{ scope.row.description || '暂无描述' }}</span></template>
          </el-table-column>
          <el-table-column label="分块策略" min-width="200">
            <template #default="scope"><span class="chunking-badge">{{ scope.row.chunkStrategy }} · {{ scope.row.chunkSize }}/{{ scope.row.chunkOverlap }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="scope"><el-tag size="small" type="success" effect="light">{{ commonStatusLabel(scope.row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="scope">
              <el-button text type="primary" @click.stop="openEdit(scope.row)">编辑</el-button>
              <el-button text type="danger" @click.stop="confirmDelete(scope.row.id, scope.row.name)">删除</el-button>
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
        title="创建知识库"
        :loading="store.actionLoading"
        :submit-error="store.actionError?.message"
        @submit="create"
      />
      <KnowledgeBaseFormDialog
        v-model="showEdit"
        title="编辑知识库"
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
import { commonStatusLabel } from '@/lib/display';

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
    await ElMessageBox.confirm(`确认删除知识库“${name}”吗？`, '删除知识库', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
    });
    await store.remove(workspaceId.value, id);
  } catch {}
}

onMounted(load);
watch(workspaceId, () => load());
</script>

<style scoped lang="scss">
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.toolbar { max-width: 560px; margin-bottom: 18px; }
.pagination { justify-content: flex-end; margin-top: 18px; }
.kb-name { color: var(--id-text); font-size: 14px; font-weight: 620; }
.kb-description { color: var(--id-text-muted); }
.chunking-badge { display: inline-flex; padding: 5px 9px; border-radius: 7px; background: #f2f4f7; color: #667085; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 11px; }
@media (max-width: 640px) { .toolbar { align-items: stretch; } }
</style>
