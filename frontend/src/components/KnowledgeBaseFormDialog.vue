<template>
  <el-dialog :model-value="modelValue" :title="title" width="560px" @update:model-value="$emit('update:modelValue', $event)">
    <el-form label-position="top">
      <el-form-item label="名称" :error="errors.name">
        <el-input v-model="form.name" maxlength="100" show-word-limit />
      </el-form-item>
      <el-form-item label="描述" :error="errors.description">
        <el-input v-model="form.description" type="textarea" :rows="3" maxlength="1000" show-word-limit />
      </el-form-item>
      <el-form-item label="分块策略" :error="errors.chunkStrategy">
        <el-select v-model="form.chunkStrategy" style="width: 100%">
          <el-option label="递归分块（Recursive）" value="RECURSIVE" />
          <el-option label="固定长度（Fixed size）" value="FIXED_SIZE" />
        </el-select>
      </el-form-item>
      <div class="chunk-grid">
        <el-form-item label="Chunk 长度" :error="errors.chunkSize">
          <el-input-number v-model="form.chunkSize" :min="100" :max="4000" controls-position="right" />
        </el-form-item>
        <el-form-item label="重叠长度" :error="errors.chunkOverlap">
          <el-input-number v-model="form.chunkOverlap" :min="0" :max="1000" controls-position="right" />
        </el-form-item>
      </div>
      <el-alert v-if="submitError" :title="submitError" type="error" :closable="false" show-icon />
    </el-form>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" @click="submit">{{ submitLabel }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import type { KnowledgeBaseRequest } from '@/types/knowledgeBase';

const props = withDefaults(defineProps<{
  modelValue: boolean;
  title: string;
  loading?: boolean;
  submitError?: string;
  initialValue?: KnowledgeBaseRequest | null;
}>(), {
  loading: false,
  submitError: '',
  initialValue: null,
});

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  submit: [value: KnowledgeBaseRequest];
}>();

const form = reactive<KnowledgeBaseRequest>({
  name: '',
  description: '',
  chunkStrategy: 'RECURSIVE',
  chunkSize: 1000,
  chunkOverlap: 150,
});
const errors = reactive<Record<string, string>>({});
const submitLabel = computed(() => props.initialValue ? '保存' : '创建');

watch(
  () => [props.modelValue, props.initialValue] as const,
  () => {
    if (!props.modelValue) return;
    Object.assign(form, {
      name: props.initialValue?.name ?? '',
      description: props.initialValue?.description ?? '',
      chunkStrategy: props.initialValue?.chunkStrategy ?? 'RECURSIVE',
      chunkSize: props.initialValue?.chunkSize ?? 1000,
      chunkOverlap: props.initialValue?.chunkOverlap ?? 150,
    });
    Object.keys(errors).forEach((key) => delete errors[key]);
  },
  { immediate: true }
);

function submit() {
  Object.keys(errors).forEach((key) => delete errors[key]);
  const name = form.name.trim();
  if (!name || name.length > 100) errors.name = '名称长度需为 1–100 个字符。';
  if ((form.description || '').length > 1000) errors.description = '描述不能超过 1000 个字符。';
  if (!['FIXED_SIZE', 'RECURSIVE'].includes(form.chunkStrategy)) errors.chunkStrategy = '请选择有效的分块策略。';
  if (!Number.isInteger(form.chunkSize) || form.chunkSize < 100 || form.chunkSize > 4000) {
    errors.chunkSize = 'Chunk 长度需为 100–4000。';
  }
  if (!Number.isInteger(form.chunkOverlap) || form.chunkOverlap < 0 || form.chunkOverlap > 1000 || form.chunkOverlap >= form.chunkSize) {
    errors.chunkOverlap = '重叠长度需为 0–1000，且小于 Chunk 长度。';
  }
  if (Object.keys(errors).length) return;
  emit('submit', {
    name,
    description: (form.description || '').trim(),
    chunkStrategy: form.chunkStrategy,
    chunkSize: form.chunkSize,
    chunkOverlap: form.chunkOverlap,
  });
}
</script>

<style scoped lang="scss">
.chunk-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
</style>
