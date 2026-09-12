<template>
  <el-dialog
    :model-value="modelValue"
    :title="secret ? 'API Key Created' : 'Create API Key'"
    width="520px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    @update:model-value="onDialogClose"
    @close="onDialogClose"
  >
    <template v-if="!secret">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="Name" required>
          <el-input
            v-model="form.name"
            placeholder="e.g. prod-integration"
            maxlength="128"
            show-word-limit
            :data-testid="`${dataId}-name`"
          />
        </el-form-item>
        <el-form-item label="Expires At (optional)">
          <el-date-picker
            v-model="form.expiresAt"
            type="datetime"
            placeholder="No expiry"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="Scope">
          <el-select v-model="form.scope" style="width: 100%" disabled>
            <el-option label="Read-only" :value="'READ'" />
          </el-select>
        </el-form-item>
        <ErrorMessage
          v-if="error"
          :message="error.message"
          :code="error.code"
          :trace-id="error.traceId"
        />
      </el-form>
    </template>

    <template v-else>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="This API key is shown only once. Copy it now."
      />
      <div class="ak-dialog__secret">
        <code class="ak-dialog__code">{{ secret.fullKey }}</code>
        <el-button
          type="primary"
          plain
          :data-testid="`${dataId}-copy`"
          @click="copySecret"
        >
          {{ copied ? 'Copied' : 'Copy' }}
        </el-button>
      </div>
    </template>

    <template #footer>
      <el-button v-if="!secret" class="ak-dialog__cancel" @click="cancel">
        Cancel
      </el-button>
      <el-button
        v-if="!secret"
        type="primary"
        :disabled="!form.name.trim()"
        :loading="submitting"
        class="ak-dialog__submit"
        @click="submit"
      >
        Create
      </el-button>
      <el-button v-else class="ak-dialog__done" @click="cancel">Done</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import ErrorMessage from '@/components/common/ErrorMessage.vue';
import type { CreateApiKeyRequest, CreateApiKeyResult } from '@/types/apiKey';
import type { ApiError } from '@/types/api';

const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    creating?: boolean;
    error?: ApiError | null;
    secret?: CreateApiKeyResult | null;
    dataId?: string;
  }>(),
  { creating: false, error: null, secret: null, dataId: 'api-key' }
);

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void;
  (e: 'create', payload: CreateApiKeyRequest): void;
  (e: 'close'): void;
}>();

const form = ref<CreateApiKeyRequest>({ name: '', scope: 'READ', expiresAt: undefined });
const submitting = computed(() => props.creating);
const copied = ref(false);

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      form.value = { name: '', scope: 'READ', expiresAt: undefined };
      copied.value = false;
    }
  }
);

function onDialogClose(value: boolean) {
  if (value) return; // open
  emit('update:modelValue', false);
  emit('close');
}

function cancel() {
  onDialogClose(false);
}

function submit() {
  if (!form.value.name.trim() || submitting.value) return;
  emit('create', {
    name: form.value.name.trim(),
    scope: 'READ',
    ...(form.value.expiresAt ? { expiresAt: form.value.expiresAt } : {}),
  });
}

async function copySecret() {
  const value = props.secret?.fullKey;
  if (!value) return;
  try {
    await navigator.clipboard.writeText(value);
    copied.value = true;
    ElMessage.success('Copied');
  } catch {
    ElMessage.error('Copy failed. Select the key and copy it manually.');
  }
}
</script>

<style scoped lang="scss">
.ak-dialog {
  &__secret {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-top: 16px;
  }
  &__code {
    flex: 1;
    font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
    word-break: break-all;
    padding: 8px 10px;
    background: #f5f7fa;
    border: 1px solid #e4e7ed;
    border-radius: 4px;
    font-size: 12px;
    color: #303133;
  }
}
</style>