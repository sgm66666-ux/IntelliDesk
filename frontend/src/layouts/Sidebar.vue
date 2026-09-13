<template>
  <aside class="sidebar">
    <div class="sidebar__eyebrow">导航</div>
    <el-menu
      :default-active="activeMenu"
      router
      class="sidebar__menu"
    >
      <el-menu-item index="/workspaces">
        <el-icon><Folder /></el-icon>
        <span>工作空间</span>
      </el-menu-item>
      <el-menu-item v-if="workspaceId" :index="`/workspaces/${workspaceId}/knowledge-bases`">
        <el-icon><Collection /></el-icon>
        <span>知识库</span>
      </el-menu-item>
      <el-menu-item v-if="workspaceId" :index="`/workspaces/${workspaceId}/conversations`">
        <el-icon><ChatDotRound /></el-icon>
        <span>智能对话</span>
      </el-menu-item>
      <el-menu-item v-if="workspaceId" :index="`/workspaces/${workspaceId}/api-keys`">
        <el-icon><Key /></el-icon>
        <span>API Key</span>
      </el-menu-item>
    </el-menu>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { ChatDotRound, Collection, Folder, Key } from '@element-plus/icons-vue';

const route = useRoute();
const workspaceId = computed(() => Number(route.params.workspaceId) || null);

const activeMenu = computed(() => {
  if (!workspaceId.value) {
    if (route.path.startsWith('/workspaces')) return '/workspaces';
    return route.path;
  }
  if (route.path.includes('/knowledge-bases')) {
    return `/workspaces/${workspaceId.value}/knowledge-bases`;
  }
  if (route.path.includes('/conversations')) {
    return `/workspaces/${workspaceId.value}/conversations`;
  }
  if (route.path.includes('/api-keys')) {
    return `/workspaces/${workspaceId.value}/api-keys`;
  }
  if (route.path === `/workspaces/${workspaceId.value}`) {
    return `/workspaces/${workspaceId.value}`;
  }
  return '/workspaces';
});
</script>

<style scoped lang="scss">
.sidebar {
  width: 232px;
  padding: 20px 12px;
  background: var(--id-surface);
  border-right: 1px solid var(--id-border);
  flex-shrink: 0;
  overflow-y: auto;

  &__eyebrow {
    padding: 0 12px 10px;
    color: var(--id-text-muted);
    font-size: 11px;
    font-weight: 650;
    letter-spacing: 0.08em;
  }

  &__menu {
    border-right: none;
    background: transparent;

    :deep(.el-menu-item) {
      height: 42px;
      margin-bottom: 4px;
      padding: 0 12px !important;
      border-radius: var(--id-radius-sm);
      color: var(--id-text-secondary);
      font-size: 14px;
      font-weight: 520;

      .el-icon { color: #778195; font-size: 17px; }
      &:hover { background: #f5f6f8; color: var(--id-text); }
      &.is-active { background: var(--id-accent-soft); color: var(--id-accent); font-weight: 620; }
      &.is-active .el-icon { color: var(--id-accent); }
    }
  }
}

@media (max-width: 900px) {
  .sidebar {
    width: 72px;
    padding-inline: 9px;
    &__eyebrow { display: none; }
    :deep(.el-menu-item) { justify-content: center; padding: 0 !important; }
    :deep(.el-menu-item span) { display: none; }
    :deep(.el-menu-item .el-icon) { margin: 0; }
  }
}
</style>
