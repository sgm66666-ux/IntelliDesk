<template>
  <div class="loading-spinner">
    <el-icon class="loading-spinner__icon" :size="size">
      <Loading />
    </el-icon>
    <span v-if="text" class="loading-spinner__text">{{ text }}</span>
  </div>
</template>

<script setup lang="ts">
import { Loading } from '@element-plus/icons-vue';

withDefaults(defineProps<{
  text?: string;
  size?: number;
}>(), {
  size: 32,
});
</script>

<style scoped lang="scss">
.loading-spinner {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px;

  &__icon {
    animation: spin 1s linear infinite;
    color: #409eff;
  }

  &__text {
    margin-top: 12px;
    color: #909399;
    font-size: 14px;
  }
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>