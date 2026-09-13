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
      <div class="assistant-answer__citations-title">引用来源 <span>{{ citations.length }}</span></div>
      <ol class="assistant-answer__citations-list">
        <li v-for="c in citations" :key="c.citationId" class="assistant-answer__citation">
          <span class="assistant-answer__citation-index">{{ c.citationId }}</span>
          <span class="assistant-answer__citation-meta">{{ c.documentName || `文档 #${c.documentId}` }}</span>
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

  &__ref { color: var(--id-accent); font-weight: 650; }

  &__typing {
    display: flex;
    gap: 4px;
    padding: 4px 0;
  }

  &__dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #98a2b3;
    animation: blink 1.2s infinite both;

    &:nth-child(2) { animation-delay: 0.2s; }
    &:nth-child(3) { animation-delay: 0.4s; }
  }

  &__citations {
    margin-top: 20px;
    padding-top: 14px;
    border-top: 1px solid var(--id-border);
  }

  &__citations-title { display: flex; align-items: center; gap: 7px; margin-bottom: 9px; color: var(--id-text-secondary); font-size: 12px; font-weight: 650; }
  &__citations-title span { display: grid; min-width: 19px; height: 19px; padding: 0 5px; place-items: center; border-radius: 999px; background: #eef1f5; color: var(--id-text-muted); font-size: 10px; }

  &__citations-list { display: grid; gap: 7px; margin: 0; padding: 0; list-style: none; font-size: 12px; }

  &__citation { position: relative; display: grid; grid-template-columns: 24px 1fr; gap: 2px 9px; padding: 11px 12px; border: 1px solid var(--id-border); border-radius: 9px; background: #fafbfc; }
  &__citation-index { grid-row: 1 / 3; display: grid; width: 22px; height: 22px; place-items: center; border-radius: 6px; background: var(--id-accent-soft); color: var(--id-accent); font-size: 10px; font-weight: 700; }

  &__citation-meta { color: var(--id-text); font-weight: 620; }

  &__citation-snippet {
    display: block;
    color: var(--id-text-muted);
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
