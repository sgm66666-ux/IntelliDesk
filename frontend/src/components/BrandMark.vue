<template>
  <div class="brand-mark" :class="{ 'brand-mark--compact': compact }">
    <span class="brand-mark__symbol" aria-hidden="true"><span /></span>
    <span class="brand-mark__copy">
      <strong>IntelliDesk</strong>
      <small v-if="!compact">Knowledge Copilot</small>
    </span>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ compact?: boolean }>(), { compact: false });
</script>

<style scoped lang="scss">
.brand-mark {
  display: inline-flex;
  align-items: center;
  gap: 11px;
  color: var(--id-text);

  &__symbol {
    position: relative;
    width: 30px;
    height: 30px;
    flex: 0 0 30px;
    border-radius: 9px;
    background: var(--id-text);
    box-shadow: 0 1px 2px rgba(16, 24, 40, 0.14);

    &::before, &::after, span { position: absolute; content: ''; background: #fff; border-radius: 2px; }
    &::before { inset: 7px 14px 7px 8px; }
    &::after { inset: 7px 8px 7px 17px; opacity: 0.58; }
    span { width: 4px; height: 4px; right: 8px; top: 7px; background: #60a5fa; }
  }

  &__copy { display: flex; flex-direction: column; line-height: 1.1; }
  strong { font-size: 16px; font-weight: 700; letter-spacing: -0.025em; }
  small { margin-top: 4px; color: var(--id-text-muted); font-size: 10px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; }
  &--compact .brand-mark__symbol { width: 28px; height: 28px; flex-basis: 28px; }
}
</style>
