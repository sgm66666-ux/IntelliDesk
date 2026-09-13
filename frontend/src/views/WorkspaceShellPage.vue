<template>
  <MainLayout>
    <div class="page-container">
      <ErrorMessage v-if="ws.error" :message="ws.error" :retry="true" @retry="loadWorkspace" />
      <div v-else>
        <div class="page-header">
          <div>
            <span class="workspace-shell__eyebrow">工作空间</span>
            <h1>{{ ws.currentWorkspace?.name || '工作空间' }}</h1>
            <p>在左侧选择知识库、智能对话或 API Key 管理。</p>
          </div>
        </div>
        <el-card class="workspace-shell__card" shadow="never">
          <span class="workspace-shell__label">空间描述</span>
          <p v-if="ws.currentWorkspace?.description">{{ ws.currentWorkspace.description }}</p>
          <p v-else class="workspace-shell__empty">暂无描述</p>
        </el-card>
      </div>
    </div>
  </MainLayout>
</template>

<script setup lang="ts">
import { onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { useWorkspaceStore } from '@/stores/workspace';
import MainLayout from '@/layouts/MainLayout.vue';
import ErrorMessage from '@/components/common/ErrorMessage.vue';

const route = useRoute();
const ws = useWorkspaceStore();

function loadWorkspace() {
  const id = Number(route.params.workspaceId);
  if (id) {
    ws.fetchDetail(id);
  }
}

onMounted(loadWorkspace);
</script>

<style scoped lang="scss">
.workspace-shell {
  &__eyebrow { display: block; margin-bottom: 8px; color: var(--id-accent); font-size: 11px; font-weight: 700; letter-spacing: 0.08em; }
  &__card { max-width: 760px; }
  &__label { display: block; margin-bottom: 12px; color: var(--id-text-muted); font-size: 12px; font-weight: 650; }
  &__card p { margin: 0; color: var(--id-text-secondary); line-height: 1.7; }
  &__empty { color: var(--id-text-muted) !important; }
}
</style>
