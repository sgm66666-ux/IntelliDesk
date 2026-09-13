import { describe, it, expect } from 'vitest';
import { shallowMount } from '@vue/test-utils';
import { defineComponent, h, inject, provide } from 'vue';
import ApiKeyCreateDialog from '@/components/api-key/ApiKeyCreateDialog.vue';
import ApiKeyTable from '@/components/api-key/ApiKeyTable.vue';
import type { ApiKey } from '@/types/apiKey';

// Element Plus stubs (lightweight) — keys must carry the el- prefix.
//
// el-table provides its data rows via provide; el-table-column consumes them
// and re-emits each row to its own scoped slot so `scope.row` resolves. This
// deliberately flattens EP's internals so the component's slot contract still
// holds under test without pulling the full Element Plus library.
const el = {
  'el-dialog': {
    template: '<div class="dialog-stub"><div class="dlg-title">{{ title }}</div><slot /><slot name="footer" /></div>',
    props: ['modelValue', 'title'],
    emits: ['update:modelValue'],
  },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<div class="fi"><slot /></div>' },
  'el-input': {
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
    props: ['modelValue', 'maxlength', 'placeholder'],
    emits: ['update:modelValue'],
  },
  'el-date-picker': { template: '<div class="datepicker-season" />' },
  'el-select': { template: '<div class="select-sim"><slot /></div>' },
  'el-option': { template: '<div class="opt-hold" />' },
  'el-button': {
    template: '<button class="ebtn" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
    props: ['disabled', 'loading', 'type', 'plain'],
    emits: ['click'],
  },
  'el-alert': { template: '<div class="alert-stub">{{ title }}<slot /></div>', props: ['title', 'type'] },
  'el-table': defineComponent({
    props: ['data'],
    setup(props, { slots }) {
      provide('tableRows', props.data);
      return () => slots.default?.();
    },
  }),
  'el-table-column': defineComponent({
    name: 'ElTableColumnStub',
    props: ['prop', 'label'],
    setup(props, { slots }) {
      const rows = inject('tableRows', []) as any[];
      return () =>
        rows.map((row) =>
          h('div', { class: 'tcol' }, [
            h('span', { class: 'tcol-label' }, String(props.label)),
            slots.default?.({ row, column: { prop: props.prop, label: props.label } }),
          ])
        );
    },
  }),
  'el-tag': { template: '<span class="tag-stub"><slot /></span>', props: ['type', 'size'] },
};

// global directives to silence the `loading` (v-loading) directive used on el-table.
const GLOBAL = { stubs: el, directives: { loading: {} } };

describe('ApiKeyCreateDialog — one-time secret safety', () => {
  const makeMount = (props = {}) =>
    shallowMount(ApiKeyCreateDialog, {
      props: { modelValue: true, ...props },
      global: GLOBAL,
    });

  it('shows the create form when no secret yet, with Create disabled on empty name', async () => {
    const wrapper = makeMount();
    expect(wrapper.text()).toContain('创建 API Key');
    await wrapper.find('.ebtn').trigger('click');
    expect(wrapper.emitted('create')).toBeUndefined();
  });

  it('emits create with the trimmed name and read-only scope', async () => {
    const wrapper = makeMount();
    const input = wrapper.find('input');
    await input.setValue('  prod-key  ');
    const submit = wrapper.find('.ak-dialog__submit');
    await submit.trigger('click');
    expect(wrapper.emitted('create')?.[0]?.[0]).toMatchObject({ name: 'prod-key', scope: 'READ' });
  });

  it('renders the one-time secret with a one-time warning and copy button', () => {
    const wrapper = shallowMount(ApiKeyCreateDialog, {
      props: {
        modelValue: true,
        secret: { id: 1, workspaceId: 7, name: 'k', keyPrefix: 'sk-x', scope: 'READ', fullKey: 'sk-x-live-secret', createdAt: '1' },
      },
      global: GLOBAL,
    });
    expect(wrapper.text()).toContain('sk-x-live-secret');
    expect(wrapper.text()).toContain('仅显示一次');
    expect(wrapper.text()).toContain('复制');
  });

  it('emits close (which clears the transient secret) when the dialog is closed', async () => {
    const wrapper = makeMount({ secret: { id: 1, workspaceId: 7, name: 'k', keyPrefix: 'sk-x', scope: 'READ', fullKey: 'sk-x', createdAt: '1' } });
    // Footer “完成” button triggers cancel() → onDialogClose(false) → emit close
    const doneBtn = wrapper.findAll('.ebtn').find((b) => b.text().includes('完成'));
    await doneBtn!.trigger('click');
    expect(wrapper.emitted('close')).toBeTruthy();
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false]);
  });
});

describe('ApiKeyTable — rendering and revoke', () => {
  const key: ApiKey = {
    id: 1, workspaceId: 7, name: 'prod', keyPrefix: 'sk-abc', scope: 'READ',
    status: 'ACTIVE', effectiveStatus: 'ACTIVE', createdAt: '2026-01-01T00:00:00Z',
  };

  it('renders name, prefix, scope — and never a fullKey', () => {
    const wrapper = shallowMount(ApiKeyTable, {
      props: { items: [key] },
      global: GLOBAL,
    });
    expect(wrapper.text()).toContain('prod');
    expect(wrapper.text()).toContain('sk-abc');
    expect(wrapper.text()).not.toContain('fullKey');
  });

  it('disables revoke for already-revoked items', () => {
    const revoked: ApiKey = { ...key, status: 'REVOKED', effectiveStatus: 'REVOKED' };
    const wrapper = shallowMount(ApiKeyTable, { props: { items: [revoked] }, global: GLOBAL });
    const btn = wrapper.findAll('.ebtn')[0];
    expect(btn.attributes('disabled')).toBeDefined();
  });

  it('disables revoke while a revoke request is in flight for that key', () => {
    const wrapper = shallowMount(ApiKeyTable, {
      props: { items: [key], revokingId: 1 },
      global: GLOBAL,
    });
    const btn = wrapper.findAll('.ebtn')[0];
    expect(btn.attributes('disabled')).toBeDefined();
  });
});
