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
  padding: 48px;
}
</style>