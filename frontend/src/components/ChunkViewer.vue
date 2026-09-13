<template>
  <section class="chunk-viewer">
    <div class="chunk-viewer__header">
      <div><h2>文档分块</h2><p>查看解析后用于检索的 Chunk 内容与来源信息。</p></div>
      <el-button :loading="loading" @click="$emit('refresh')">刷新</el-button>
    </div>
    <ErrorMessage v-if="error" :message="error" :retry="true" @retry="$emit('refresh')" />
    <EmptyState v-else-if="!loading && chunks.length === 0" description="暂无可用 Chunk" />
    <div v-else class="chunk-list">
      <el-card v-for="chunk in chunks" :key="chunk.id" class="chunk-card" shadow="never">
        <template #header>
          <div class="chunk-card__header">
            <strong><span class="chunk-card__index">{{ chunk.chunkIndex }}</span>Chunk</strong>
            <span>{{ chunk.characterCount ?? '—' }} 字符 · {{ chunk.tokenCount ?? '—' }} tokens</span>
          </div>
        </template>
        <pre class="chunk-card__content">{{ chunk.content }}</pre>
        <div v-if="chunk.pageStart !== null || chunk.pageEnd !== null || chunk.sectionPath" class="chunk-card__meta">
          <span v-if="chunk.pageStart !== null && chunk.pageStart !== undefined">第 {{ chunk.pageStart }} 页</span>
          <span v-if="chunk.pageEnd !== null && chunk.pageEnd !== undefined">- {{ chunk.pageEnd }}</span>
          <span v-if="chunk.sectionPath">{{ chunk.sectionPath }}</span>
        </div>
        <el-collapse v-if="chunk.sourceMetadata">
          <el-collapse-item title="来源元数据">
            <pre class="chunk-card__metadata">{{ stringify(chunk.sourceMetadata) }}</pre>
          </el-collapse-item>
        </el-collapse>
      </el-card>
    </div>
    <el-pagination
      v-if="total > size"
      layout="prev, pager, next"
      :current-page="page"
      :page-size="size"
      :total="total"
      @current-change="$emit('page-change', $event)"
    />
  </section>
</template>

<script setup lang="ts">
import type { DocumentChunk } from '@/types/document';
import EmptyState from '@/components/common/EmptyState.vue';
import ErrorMessage from '@/components/common/ErrorMessage.vue';

defineProps<{
  chunks: DocumentChunk[];
  loading: boolean;
  error?: string;
  page: number;
  size: number;
  total: number;
}>();

defineEmits<{
  refresh: [];
  'page-change': [page: number];
}>();

function stringify(value: Record<string, unknown>) {
  return JSON.stringify(value, null, 2);
}
</script>

<style scoped lang="scss">
.chunk-viewer {
  margin-top: 30px;

  &__header,
  .chunk-card__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  &__header { margin-bottom: 16px; }
  &__header h2 { margin: 0; color: var(--id-text); font-size: 18px; }
  &__header p { margin: 5px 0 0; color: var(--id-text-muted); font-size: 13px; }
}

.chunk-list {
  display: grid;
  gap: 10px;
  margin-bottom: 16px;
}

.chunk-card {
  border-color: transparent;
  background: var(--id-surface);
  box-shadow: var(--id-shadow-sm);

  :deep(.el-card__header) { padding: 14px 18px; border-bottom-color: #eff1f4; }
  :deep(.el-card__body) { padding: 18px; }

  &__header strong { display: flex; align-items: center; gap: 9px; color: var(--id-text); font-size: 13px; }
  &__header > span { color: var(--id-text-muted); font-size: 11px; }
  &__index { display: grid; min-width: 24px; height: 24px; padding: 0 5px; place-items: center; border-radius: 6px; background: var(--id-accent-soft); color: var(--id-accent); font-size: 11px; }

  &__content,
  &__metadata {
    margin: 0;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    font-family: inherit;
    color: #344054;
    font-size: 13px;
    line-height: 1.75;
  }

  &__meta {
    display: flex;
    gap: 8px;
    margin: 12px 0;
    color: var(--id-text-muted);
    font-size: 12px;
  }
}

@media (max-width: 640px) { .chunk-viewer__header { align-items: flex-start; flex-direction: column; } }
</style>
