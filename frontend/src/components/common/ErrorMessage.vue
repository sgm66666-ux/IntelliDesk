<template>
  <div class="error-message">
    <el-alert
      :title="message"
      type="error"
      show-icon
      :closable="false"
    />
    <div v-if="code !== undefined || traceId" class="error-details">
      <span v-if="code !== undefined">错误代码：{{ code }}</span>
      <span v-if="traceId">Trace ID: {{ traceId }}</span>
    </div>
    <el-button v-if="retry" type="primary" size="small" @click="$emit('retry')" style="margin-top: 12px">
      重试
    </el-button>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  message: string;
  code?: number | string;
  traceId?: string;
  retry?: boolean;
}>();

defineEmits<{
  retry: [];
}>();
</script>

<style scoped lang="scss">
.error-message {
  padding: 22px;
  max-width: 560px;
  margin: 0 auto;
  border-radius: var(--id-radius);
  background: #fff;
}

.error-details {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
  color: var(--id-text-muted);
  font-size: 12px;
}
</style>
