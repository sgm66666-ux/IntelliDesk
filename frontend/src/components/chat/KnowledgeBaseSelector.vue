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
        KB scope ({{ selected.length }})<el-icon class="kb-selector__arrow"><ArrowDown /></el-icon>
      </el-button>
    </template>

    <div class="kb-selector">
      <div v-if="loading" class="kb-selector__hint">Loading knowledge bases…</div>
      <div v-else-if="items.length === 0" class="kb-selector__hint">
        No knowledge bases in this workspace. Create one to enable RAG chat.
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
          <el-button size="small" @click="selected = allIds">Select all</el-button>
          <el-button size="small" @click="selected = []">Clear</el-button>
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
  &__hint { color: #909399; font-size: 13px; padding: 8px; }

  &__item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 4px;
    cursor: pointer;
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
