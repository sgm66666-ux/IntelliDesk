<template>
  <div class="error-message">
    <el-alert
      :title="message"
      type="error"
      show-icon
      :closable="false"
    />
    <div v-if="code !== undefined || traceId" class="error-details">
      <span v-if="code !== undefined">Code: {{ code }}</span>
      <span v-if="traceId">Trace ID: {{ traceId }}</span>
    </div>
    <el-button v-if="retry" type="primary" size="small" @click="$emit('retry')" style="margin-top: 12px">
      Retry
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
  padding: 24px;
  max-width: 480px;
  margin: 0 auto;
}

.error-details {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
  color: #606266;
  font-size: 12px;
}
</style>