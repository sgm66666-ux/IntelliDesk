<template>
  <div class="mode-toggle" role="tablist" aria-label="Chat mode">
    <button
      type="button"
      class="mode-toggle__btn"
      :class="{ 'mode-toggle__btn--active': modelValue === 'rag' }"
      :aria-selected="modelValue === 'rag'"
      :disabled="disabled"
      @click="$emit('update:modelValue', 'rag')"
    >
      RAG Chat
    </button>
    <button
      type="button"
      class="mode-toggle__btn"
      :class="{ 'mode-toggle__btn--active': modelValue === 'agent' }"
      :aria-selected="modelValue === 'agent'"
      :disabled="disabled"
      @click="$emit('update:modelValue', 'agent')"
    >
      Agent
    </button>
  </div>
</template>

<script setup lang="ts">
import type { ChatMode } from '@/types/chat';

defineProps<{
  modelValue: ChatMode;
  disabled?: boolean;
}>();

defineEmits<{
  'update:modelValue': [mode: ChatMode];
}>();
</script>

<style scoped lang="scss">
.mode-toggle {
  display: inline-flex;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
  background: #f5f7fa;

  &__btn {
    border: none;
    background: transparent;
    padding: 6px 16px;
    font-size: 13px;
    color: #606266;
    cursor: pointer;

    &--active {
      background: #409eff;
      color: #fff;
    }

    &:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }
  }
}
</style>