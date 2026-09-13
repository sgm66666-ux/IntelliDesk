<template>
  <el-tag :type="tagType" effect="light">{{ label }}</el-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { DocumentStatus } from '@/types/document';
import { documentStatusLabel } from '@/lib/display';

const props = defineProps<{ status?: DocumentStatus | string | null }>();

const tagTypes: Record<string, 'info' | 'warning' | 'success' | 'danger'> = {
  UPLOADING: 'info',
  PENDING: 'warning',
  PROCESSING: 'warning',
  COMPLETED: 'success',
  FAILED: 'danger',
  DELETING: 'info',
};

const label = computed(() => documentStatusLabel(props.status));
const tagType = computed(() => tagTypes[props.status || ''] || 'info');
</script>
