<template>
  <el-popover
    placement="bottom-start"
    :width="280"
    trigger="click"
    :visible="visible"
    @update:visible="visible = $event"
  >
    <template #reference>
      <el-button :disabled="disabled" size="small">
        知识库范围（{{ selected.length }}）<el-icon class="kb-selector__arrow"><ArrowDown /></el-icon>
      </el-button>
    </template>

    <div class="kb-selector">
      <div v-if="loading" class="kb-selector__hint">正在加载知识库…</div>
      <div v-else-if="items.length === 0" class="kb-selector__hint">
        此工作空间暂无知识库，创建后即可使用 RAG 对话。
      </div>
      <div v-else>
        <div
          v-for="kb in items"
          :key="kb.id"
          class="kb-selector__item"
          @click="toggle(kb.id)"
        >
          <el-checkbox :model-value="isSelected(kb.id)" @click.stop @change="toggle(kb.id)" />
          <span class="kb-selector__name">{{ kb.name }}</span>
        </div>
        <div class="kb-selector__actions">
          <el-button size="small" @click="selected = allIds">全选</el-button>
          <el-button size="small" @click="selected = []">清空</el-button>
        </div>
      </div>
    </div>
  </el-popover>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { ArrowDown } from '@element-plus/icons-vue';
import type { KnowledgeBase } from '@/types/knowledgeBase';

const props = defineProps<{
  items: KnowledgeBase[];
  loading?: boolean;
  disabled?: boolean;
}>();

const selected = ref<number[]>([]);
const visible = ref(false);
const allIds = ref<number[]>(props.items.map((kb) => kb.id));

watch(
  () => props.items,
  (items) => {
    allIds.value = items.map((kb) => kb.id);
  }
);

function isSelected(id: number) {
  return selected.value.includes(id);
}

function toggle(id: number) {
  selected.value = selected.value.includes(id)
    ? selected.value.filter((v) => v !== id)
    : [...selected.value, id];
}

defineExpose({ selected, visible });
</script>

<style scoped lang="scss">
.kb-selector {
  &__hint { color: var(--id-text-muted); font-size: 13px; line-height: 1.6; padding: 8px; }

  &__item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 5px;
    border-radius: 7px;
    cursor: pointer;
    &:hover { background: #f6f7f9; }
  }

  &__name { font-size: 13px; }

  &__actions {
    display: flex;
    gap: 8px;
    margin-top: 8px;
    justify-content: flex-end;
  }

  &__arrow { margin-left: 4px; }
}
</style>
