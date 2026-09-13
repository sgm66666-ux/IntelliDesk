<template>
  <div class="chat-composer">
    <el-input
      v-model="text"
      type="textarea"
      :rows="3"
      :placeholder="disabled ? '正在等待当前回答…' : '输入你的问题，按 Enter 发送'"
      resize="none"
      :disabled="disabled"
      @keydown.enter.exact.prevent="submit"
    />
    <div class="chat-composer__actions">
      <span class="chat-composer__hint">Enter 发送 · Shift + Enter 换行</span>
      <el-button v-if="!streaming" type="primary" :disabled="disabled || !canSend" @click="submit">
        发送
      </el-button>
      <el-button v-else type="warning" @click="$emit('stop')">
        停止生成
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';

const props = defineProps<{
  disabled?: boolean;
  streaming?: boolean;
}>();

const emit = defineEmits<{
  send: [text: string];
  stop: [];
}>();

const text = ref('');
const trimmed = computed(() => text.value.trim());
const canSend = computed(() => trimmed.value.length > 0 && !props.disabled);

function submit() {
  if (!props.streaming && canSend.value) {
    const value = trimmed.value;
    text.value = '';
    emit('send', value);
  }
}

// Reset the draft when a new stream begins or the conversation switches.
watch(
  () => [props.disabled, props.streaming] as const,
  () => {
    if (props.disabled) text.value = '';
  }
);
</script>

<style scoped lang="scss">
.chat-composer {
  border: 1px solid var(--id-border-strong);
  border-radius: 14px;
  background: #fff;
  padding: 10px 12px 11px;
  box-shadow: 0 8px 28px rgba(16, 24, 40, 0.06);

  &:focus-within { border-color: #9bb7f5; box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.08), 0 8px 28px rgba(16, 24, 40, 0.06); }
  :deep(.el-textarea__inner) { min-height: 70px !important; padding: 8px 4px; border: 0; box-shadow: none; color: var(--id-text); line-height: 1.6; }
  :deep(.el-textarea__inner::placeholder) { color: #a2aab7; }

  &__actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 8px;
  }

  &__hint { font-size: 11px; color: var(--id-text-muted); }
}
</style>
