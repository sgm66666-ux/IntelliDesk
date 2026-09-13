import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import LoadingSpinner from '@/components/common/LoadingSpinner.vue';
import EmptyState from '@/components/common/EmptyState.vue';
import ErrorMessage from '@/components/common/ErrorMessage.vue';

describe('LoadingSpinner', () => {
  it('renders with default props', () => {
    const wrapper = mount(LoadingSpinner, {
      global: {
        stubs: {
          'el-icon': { template: '<span class="el-icon-stub"><slot /></span>' },
        },
      },
    });
    expect(wrapper.find('.loading-spinner').exists()).toBe(true);
  });

  it('renders text prop', () => {
    const wrapper = mount(LoadingSpinner, {
      props: { text: 'Loading...' },
      global: {
        stubs: {
          'el-icon': { template: '<span class="el-icon-stub"><slot /></span>' },
        },
      },
    });
    expect(wrapper.text()).toContain('Loading...');
  });
});

describe('EmptyState', () => {
  it('renders description', () => {
    const wrapper = mount(EmptyState, {
      props: { description: 'No items' },
      global: {
        stubs: {
          'el-empty': { template: '<div class="el-empty-stub">{{ description }}</div>', props: ['description'] },
          'el-button': { template: '<button class="el-button-stub"><slot /></button>' },
        },
      },
    });
    expect(wrapper.text()).toContain('No items');
  });

  it('shows action button when actionLabel is provided', () => {
    const wrapper = mount(EmptyState, {
      props: { actionLabel: 'Create' },
      global: {
        stubs: {
          'el-empty': { template: '<div class="el-empty-stub">{{ description }}</div>', props: ['description'] },
          'el-button': { template: '<button class="el-button-stub"><slot /></button>' },
        },
      },
    });
    expect(wrapper.find('.el-button-stub').exists()).toBe(true);
  });
});

describe('ErrorMessage', () => {
  const stubs = {
    'el-alert': { template: '<div class="el-alert-stub">{{ title }}</div>', props: ['title', 'type', 'show-icon', 'closable'] },
    'el-button': { template: '<button class="el-button-stub"><slot /></button>' },
  };

  it('renders error message', () => {
    const wrapper = mount(ErrorMessage, {
      props: { message: 'Something went wrong' },
      global: { stubs },
    });
    expect(wrapper.text()).toContain('Something went wrong');
  });

  it('shows retry button when retry is true', () => {
    const wrapper = mount(ErrorMessage, {
      props: { message: 'Error', retry: true },
      global: { stubs },
    });
    expect(wrapper.find('.el-button-stub').exists()).toBe(true);
  });

  it('renders sanitized code and trace ID when provided', () => {
    const wrapper = mount(ErrorMessage, {
      props: { message: 'Request failed', code: 4008, traceId: 'trace-document-action' },
      global: { stubs },
    });

    expect(wrapper.text()).toContain('Request failed');
    expect(wrapper.text()).toContain('错误代码：4008');
    expect(wrapper.text()).toContain('Trace ID: trace-document-action');
  });
});
