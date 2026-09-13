<template>
  <div class="mode-toggle" role="tablist" aria-label="对话模式">
    <button
      type="button"
      class="mode-toggle__btn"
      :class="{ 'mode-toggle__btn--active': modelValue === 'rag' }"
      :aria-selected="modelValue === 'rag'"
      :disabled="disabled"
      @click="$emit('update:modelValue', 'rag')"
    >
      RAG 对话
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
  padding: 3px;
  border: 1px solid var(--id-border);
  border-radius: 9px;
  background: #f4f5f7;

  &__btn {
    border: none;
    background: transparent;
    padding: 6px 14px;
    border-radius: 6px;
    font-size: 13px;
    color: var(--id-text-secondary);
    cursor: pointer;

    &--active {
      background: #fff;
      color: var(--id-text);
      box-shadow: 0 1px 3px rgba(16, 24, 40, 0.09);
    }

    &:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }
  }
}
</style>
