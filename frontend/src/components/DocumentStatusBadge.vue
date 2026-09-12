<template>
  <el-tag :type="tagType" effect="light">{{ label }}</el-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { DocumentStatus } from '@/types/document';

const props = defineProps<{ status?: DocumentStatus | string | null }>();

const labels: Record<string, string> = {
  UPLOADING: 'Uploading',
  PENDING: 'Accepted / waiting for processing',
  PROCESSING: 'Processing',
  COMPLETED: 'Chunks persisted',
  FAILED: 'Processing failed',
  DELETING: 'Deleting',
};

const tagTypes: Record<string, 'info' | 'warning' | 'success' | 'danger'> = {
  UPLOADING: 'info',
  PENDING: 'warning',
  PROCESSING: 'warning',
  COMPLETED: 'success',
  FAILED: 'danger',
  DELETING: 'info',
};

const label = computed(() => labels[props.status || ''] || 'Unknown status');
const tagType = computed(() => tagTypes[props.status || ''] || 'info');
</script>
