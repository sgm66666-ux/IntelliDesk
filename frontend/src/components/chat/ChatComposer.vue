<template>
  <div class="chat-composer">
    <el-input
      v-model="text"
      type="textarea"
      :rows="3"
      :placeholder="disabled ? 'Waiting for current answer…' : 'Type a message (Enter to send, Shift+Enter for newline)'"
      resize="none"
      :disabled="disabled"
      @keydown.enter.exact.prevent="submit"
    />
    <div class="chat-composer__actions">
      <span class="chat-composer__hint">Enter to send · Shift+Enter for newline</span>
      <el-button v-if="!streaming" type="primary" :disabled="disabled || !canSend" @click="submit">
        Send
      </el-button>
      <el-button v-else type="warning" @click="$emit('stop')">
        Stop
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
  border: 1px solid #e4e7ed;
  border-radius: 10px;
  background: #fff;
  padding: 10px;

  &__actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 8px;
  }

  &__hint { font-size: 12px; color: #c0c4cc; }
}
</style>