<template>
  <div class="assistant-answer">
    <!-- Content rendered as safe text; inline [n] citation markers become
         numeric superscripts. NO v-html / innerHTML is used anywhere. -->
    <div v-if="content" class="assistant-answer__content">
      <template v-for="(part, i) in segments" :key="i">
        <sup v-if="part.ref" class="assistant-answer__ref">[{{ part.ref }}]</sup>
        <span v-else class="assistant-answer__text">{{ part.text }}</span>
      </template>
    </div>
    <div v-else-if="streaming" class="assistant-answer__typing">
      <span class="assistant-answer__dot" />
      <span class="assistant-answer__dot" />
      <span class="assistant-answer__dot" />
    </div>

    <slot name="tools" />

    <div v-if="citations.length" class="assistant-answer__citations">
      <div class="assistant-answer__citations-title">References</div>
      <ol class="assistant-answer__citations-list">
        <li v-for="c in citations" :key="c.citationId" class="assistant-answer__citation">
          <span class="assistant-answer__citation-meta">{{ c.documentName || `Document #${c.documentId}` }}</span>
          <span v-if="c.content" class="assistant-answer__citation-snippet">{{ c.content }}</span>
        </li>
      </ol>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { CitationItem } from '@/types/conversation';

const props = defineProps<{
  content: string;
  citations: CitationItem[];
  streaming?: boolean;
}>();

/** Split content so "[3]" style markers render as safe numeric superscripts. */
const segments = computed(() => {
  const parts = props.content.split(/(\[\d+\])/g).filter(Boolean);
  return parts.map((part) => {
    const match = /^\[(\d+)\]$/.exec(part);
    return match ? { ref: Number(match[1]) } : { text: part };
  });
});
</script>

<style scoped lang="scss">
.assistant-answer {
  &__content {
    white-space: pre-wrap;
    word-break: break-word;
    line-height: 1.6;
  }

  &__text { white-space: pre-wrap; }

  &__ref { color: #409eff; font-weight: 600; }

  &__typing {
    display: flex;
    gap: 4px;
    padding: 4px 0;
  }

  &__dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #c0c4cc;
    animation: blink 1.2s infinite both;

    &:nth-child(2) { animation-delay: 0.2s; }
    &:nth-child(3) { animation-delay: 0.4s; }
  }

  &__citations {
    margin-top: 12px;
    border-top: 1px solid #ebeef5;
    padding-top: 8px;
  }

  &__citations-title { font-size: 12px; color: #909399; margin-bottom: 4px; }

  &__citations-list { margin: 0; padding-left: 20px; font-size: 12px; color: #606266; }

  &__citation-meta { font-weight: 600; color: #303133; }

  &__citation-snippet {
    display: block;
    color: #909399;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}

@keyframes blink {
  0%, 80%, 100% { opacity: 0.2; }
  40% { opacity: 1; }
}
</style>