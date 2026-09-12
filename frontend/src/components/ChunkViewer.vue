<template>
  <section class="chunk-viewer">
    <div class="chunk-viewer__header">
      <h2>Chunks</h2>
      <el-button :loading="loading" @click="$emit('refresh')">Refresh chunks</el-button>
    </div>
    <ErrorMessage v-if="error" :message="error" :retry="true" @retry="$emit('refresh')" />
    <EmptyState v-else-if="!loading && chunks.length === 0" description="No chunks available" />
    <div v-else class="chunk-list">
      <el-card v-for="chunk in chunks" :key="chunk.id" class="chunk-card" shadow="never">
        <template #header>
          <div class="chunk-card__header">
            <strong>Chunk {{ chunk.chunkIndex }}</strong>
            <span>{{ chunk.characterCount ?? '—' }} characters · {{ chunk.tokenCount ?? '—' }} tokens</span>
          </div>
        </template>
        <pre class="chunk-card__content">{{ chunk.content }}</pre>
        <div v-if="chunk.pageStart !== null || chunk.pageEnd !== null || chunk.sectionPath" class="chunk-card__meta">
          <span v-if="chunk.pageStart !== null && chunk.pageStart !== undefined">Page {{ chunk.pageStart }}</span>
          <span v-if="chunk.pageEnd !== null && chunk.pageEnd !== undefined">- {{ chunk.pageEnd }}</span>
          <span v-if="chunk.sectionPath">{{ chunk.sectionPath }}</span>
        </div>
        <el-collapse v-if="chunk.sourceMetadata">
          <el-collapse-item title="Source metadata">
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
  margin-top: 24px;

  &__header,
  .chunk-card__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }
}

.chunk-list {
  display: grid;
  gap: 12px;
  margin-bottom: 16px;
}

.chunk-card {
  &__content,
  &__metadata {
    margin: 0;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    font-family: inherit;
  }

  &__meta {
    display: flex;
    gap: 8px;
    margin: 12px 0;
    color: #606266;
    font-size: 13px;
  }
}
</style>
