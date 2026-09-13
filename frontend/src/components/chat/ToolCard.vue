<template>
  <div class="tool-card" :class="`tool-card--${status}`">
    <div class="tool-card__header">
      <el-icon v-if="status === 'running'" class="tool-card__spin"><Loading /></el-icon>
      <el-icon v-else-if="status === 'done'" class="tool-card__ok"><CircleCheckFilled /></el-icon>
      <el-icon v-else-if="status === 'error'" class="tool-card__err"><CircleCloseFilled /></el-icon>
      <span class="tool-card__name">{{ toolName }}</span>
      <el-tag size="small" :type="tagType" class="tool-card__badge">{{ statusLabel }}</el-tag>
    </div>
    <div v-if="resultSummary" class="tool-card__result">
      {{ resultSummary }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { Loading, CircleCheckFilled, CircleCloseFilled } from '@element-plus/icons-vue';
import type { ToolTraceItem } from '@/types/chat';

const props = defineProps<{ item: ToolTraceItem }>();

const status = computed(() => props.item.status);
const toolName = computed(() => props.item.toolName);

const tagType = computed(() => {
  switch (props.item.status) {
    case 'done':
      return 'success';
    case 'error':
      return 'danger';
    default:
      return 'info';
  }
});

const statusLabel = computed(() => {
  switch (props.item.status) {
    case 'done':
      return props.item.success ? '已完成' : '失败';
    case 'error':
      return '失败';
    default:
      return '调用中…';
  }
});

const resultSummary = computed(() => {
  const item = props.item;
  if (item.status !== 'done') return '';
  const parts: string[] = [];
  if (typeof item.resultCount === 'number') parts.push(`${item.resultCount} 条结果`);
  if (typeof item.durationMs === 'number') parts.push(`${item.durationMs}ms`);
  if (parts.length === 0) return item.success ? '已完成' : '失败';
  return parts.join(' · ');
});
</script>

<style scoped lang="scss">
.tool-card {
  border: 1px solid var(--id-border);
  border-radius: 9px;
  padding: 9px 11px;
  margin: 8px 0;
  font-size: 13px;
  background: #fafbfc;

  &__header {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  &__spin { animation: spin 1s linear infinite; color: var(--id-accent); }
  &__ok { color: var(--id-success); }
  &__err { color: var(--id-danger); }

  &__name { font-weight: 600; }

  &__badge { margin-left: auto; }

  &__result {
    margin-top: 6px;
    color: var(--id-text-muted);
    white-space: pre-wrap;
    word-break: break-word;
  }
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
