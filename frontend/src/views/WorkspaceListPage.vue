<template>
  <MainLayout>
    <div class="page-container">
    <div class="page-header">
      <h1>Workspaces</h1>
      <el-button type="primary" @click="showCreateDialog = true">Create Workspace</el-button>
    </div>

    <LoadingSpinner v-if="ws.loading" text="Loading workspaces..." />
    <ErrorMessage v-else-if="ws.error" :message="ws.error" :retry="true" @retry="ws.fetchList()" />
    <EmptyState
      v-else-if="ws.workspaces.length === 0"
      description="No workspaces yet"
      action-label="Create your first workspace"
      @action="showCreateDialog = true"
    />
    <div v-else class="workspace-grid">
      <el-card
        v-for="w in ws.workspaces"
        :key="w.id"
        class="workspace-card"
        shadow="hover"
        @click="router.push(`/workspaces/${w.id}`)"
      >
        <template #header>
          <div class="workspace-card__header">
            <span>{{ w.name }}</span>
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
        <p class="workspace-card__desc">{{ w.description || 'No description' }}</p>
      </el-card>
    </div>

    <el-dialog v-model="showCreateDialog" title="Create Workspace" width="480px">
      <el-form @submit.prevent="handleCreate" label-position="top">
        <el-form-item label="Name" required>
          <el-input v-model="newName" placeholder="Workspace name" />
        </el-form-item>
        <el-form-item label="Description">
          <el-input v-model="newDesc" type="textarea" placeholder="Optional description" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">Cancel</el-button>
        <el-button type="primary" :loading="ws.loading" @click="handleCreate">Create</el-button>
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
      `Are you sure you want to delete "${workspace.name}"? This action cannot be undone.`,
      'Delete Workspace',
      { confirmButtonText: 'Delete', cancelButtonText: 'Cancel', type: 'warning' }
    );
    await ws.remove(workspace.id);
  } catch {
    // cancelled or error
  }
}
</script>

<style scoped lang="scss">
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.workspace-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}

.workspace-card {
  cursor: pointer;
  transition: transform 0.2s;

  &:hover {
    transform: translateY(-2px);
  }

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__desc {
    color: #909399;
    font-size: 14px;
  }
}
</style>