<template>
  <div class="document-upload">
    <el-upload
      :auto-upload="false"
      :show-file-list="false"
      :disabled="loading"
      accept=".pdf,.md,.markdown,.txt,text/plain,text/markdown,application/pdf"
      @change="selectFile"
    >
      <el-button type="primary" :loading="loading">Upload document</el-button>
    </el-upload>
    <span v-if="file" class="document-upload__name">{{ file.name }}</span>
    <el-button v-if="file" type="primary" :loading="loading" @click="submit">Send</el-button>
    <el-progress v-if="loading" :percentage="progress" :show-text="true" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import type { UploadFile } from 'element-plus';

const props = defineProps<{ loading: boolean; progress: number }>();
const emit = defineEmits<{ upload: [file: File] }>();
const file = ref<File | null>(null);

function selectFile(uploadFile: UploadFile) {
  file.value = uploadFile.raw ?? null;
}

function submit() {
  if (!file.value || props.loading) return;
  emit('upload', file.value);
}
</script>

<style scoped lang="scss">
.document-upload {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;

  &__name {
    max-width: 320px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
</style>
