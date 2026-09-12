<template>
  <MainLayout>
    <div class="page-container">
      <ErrorMessage v-if="ws.error" :message="ws.error" :retry="true" @retry="loadWorkspace" />
      <div v-else>
        <div class="page-header">
          <h1>{{ ws.currentWorkspace?.name || 'Workspace' }}</h1>
        </div>
        <el-card>
          <p v-if="ws.currentWorkspace?.description">{{ ws.currentWorkspace.description }}</p>
          <p v-else style="color: #909399">No description</p>
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