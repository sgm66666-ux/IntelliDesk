<template>
  <div class="empty-state">
    <el-empty :description="description" />
    <el-button v-if="actionLabel" type="primary" @click="$emit('action')">
      {{ actionLabel }}
    </el-button>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  description?: string;
  actionLabel?: string;
}>();

defineEmits<{
  action: [];
}>();
</script>

<style scoped lang="scss">
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 280px;
  padding: 48px;
  border: 1px dashed var(--id-border-strong);
  border-radius: var(--id-radius);
  background: rgba(255, 255, 255, 0.64);

  :deep(.el-empty__description p) { color: var(--id-text-muted); font-size: 13px; }
}
</style>
