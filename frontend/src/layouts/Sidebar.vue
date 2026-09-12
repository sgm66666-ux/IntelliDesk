<template>
  <aside class="sidebar">
    <el-menu
      :default-active="activeMenu"
      router
      class="sidebar__menu"
    >
      <el-menu-item index="/workspaces">
        <el-icon><Folder /></el-icon>
        <span>Workspaces</span>
      </el-menu-item>
      <el-menu-item v-if="workspaceId" :index="`/workspaces/${workspaceId}/knowledge-bases`">
        <el-icon><Collection /></el-icon>
        <span>Knowledge Bases</span>
      </el-menu-item>
      <el-menu-item v-if="workspaceId" :index="`/workspaces/${workspaceId}/conversations`">
        <el-icon><ChatDotRound /></el-icon>
        <span>Chat</span>
      </el-menu-item>
      <el-menu-item v-if="workspaceId" :index="`/workspaces/${workspaceId}/api-keys`">
        <el-icon><Key /></el-icon>
        <span>API Keys</span>
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
  width: 220px;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  flex-shrink: 0;
  overflow-y: auto;

  &__menu {
    border-right: none;
  }
}
</style>