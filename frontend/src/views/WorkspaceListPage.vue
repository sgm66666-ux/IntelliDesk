<template>
  <MainLayout>
    <div class="page-container">
    <div class="page-header">
      <div>
        <h1>工作空间</h1>
        <p>按项目组织知识库、对话与 API 访问凭证。</p>
      </div>
      <el-button type="primary" @click="showCreateDialog = true">创建工作空间</el-button>
    </div>

    <LoadingSpinner v-if="ws.loading" text="正在加载工作空间…" />
    <ErrorMessage v-else-if="ws.error" :message="ws.error" :retry="true" @retry="ws.fetchList()" />
    <EmptyState
      v-else-if="ws.workspaces.length === 0"
      description="还没有工作空间"
      action-label="创建第一个工作空间"
      @action="showCreateDialog = true"
    />
    <div v-else class="workspace-grid">
      <el-card
        v-for="w in ws.workspaces"
        :key="w.id"
        class="workspace-card"
        shadow="never"
        @click="router.push(`/workspaces/${w.id}`)"
      >
        <template #header>
          <div class="workspace-card__header">
            <div class="workspace-card__identity">
              <span class="workspace-card__icon">W</span>
              <strong>{{ w.name }}</strong>
            </div>
            <el-button
              type="danger"
              size="small"
              text
              @click.stop="handleDelete(w)"
            >
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
        </template>
        <p class="workspace-card__desc">{{ w.description || '暂无描述' }}</p>
        <div class="workspace-card__footer">进入工作空间 <span>→</span></div>
      </el-card>
    </div>

    <el-dialog v-model="showCreateDialog" title="创建工作空间" width="480px">
      <el-form @submit.prevent="handleCreate" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="newName" placeholder="输入工作空间名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="newDesc" type="textarea" placeholder="可选，用一句话说明用途" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" :loading="ws.loading" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>
    </div>
  </MainLayout>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { Delete } from '@element-plus/icons-vue';
import { useWorkspaceStore } from '@/stores/workspace';
import LoadingSpinner from '@/components/common/LoadingSpinner.vue';
import EmptyState from '@/components/common/EmptyState.vue';
import ErrorMessage from '@/components/common/ErrorMessage.vue';
import MainLayout from '@/layouts/MainLayout.vue';
import { ElMessageBox } from 'element-plus';

const ws = useWorkspaceStore();
const router = useRouter();

const showCreateDialog = ref(false);
const newName = ref('');
const newDesc = ref('');

onMounted(() => {
  ws.fetchList();
});

async function handleCreate() {
  if (!newName.value.trim()) return;
  try {
    await ws.create({ name: newName.value.trim(), description: newDesc.value.trim() });
    showCreateDialog.value = false;
    newName.value = '';
    newDesc.value = '';
  } catch {
    // error handled by store
  }
}

async function handleDelete(workspace: { id: number; name: string }) {
  try {
    await ElMessageBox.confirm(
      `确认删除工作空间“${workspace.name}”吗？此操作无法撤销。`,
      '删除工作空间',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    );
    await ws.remove(workspace.id);
  } catch {
    // cancelled or error
  }
}
</script>

<style scoped lang="scss">
.workspace-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(290px, 1fr));
  gap: 18px;
}

.workspace-card {
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;

  &:hover {
    transform: translateY(-2px);
    border-color: #cfd5df;
    box-shadow: var(--id-shadow-card);
  }

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__identity { display: flex; align-items: center; gap: 10px; color: var(--id-text); }

  &__icon {
    display: grid;
    width: 30px;
    height: 30px;
    place-items: center;
    border-radius: 8px;
    background: var(--id-accent-soft);
    color: var(--id-accent);
    font-size: 12px;
    font-weight: 700;
  }

  &__desc {
    min-height: 44px;
    margin: 0;
    color: var(--id-text-muted);
    font-size: 14px;
    line-height: 1.6;
  }

  &__footer {
    display: flex;
    justify-content: space-between;
    margin-top: 20px;
    padding-top: 14px;
    border-top: 1px solid var(--id-border);
    color: var(--id-text-secondary);
    font-size: 12px;
    span { color: var(--id-accent); }
  }
}
</style>
