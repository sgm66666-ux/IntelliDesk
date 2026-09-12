import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { reactive } from 'vue';
import DocumentDetailPage from '@/views/DocumentDetailPage.vue';

const mocks = vi.hoisted(() => ({
  fetchDetail: vi.fn(),
  fetchChunks: vi.fn(),
  startPolling: vi.fn(),
  stopPolling: vi.fn(),
  clearDetail: vi.fn(),
  retry: vi.fn(),
  remove: vi.fn(),
  push: vi.fn(),
  confirm: vi.fn(),
}));

const failedDocument = {
  id: 3,
  fileName: 'failed.txt',
  status: 'FAILED',
  fileSize: 16,
  contentType: 'text/plain',
};

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { workspaceId: '1', knowledgeBaseId: '2', documentId: '3' } }),
  useRouter: () => ({ push: mocks.push }),
}));

vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: mocks.confirm },
}));

const documentStore = reactive({
  currentDocument: failedDocument as any,
  detailLoading: false,
  detailError: null as any,
  chunks: [] as any[],
  chunksLoading: false,
  chunksError: null as any,
  chunkPage: 1,
  chunkSize: 20,
  chunkTotal: 0,
  actionLoading: false,
  actionError: null as any,
  fetchDetail: mocks.fetchDetail,
  fetchChunks: mocks.fetchChunks,
  startPolling: mocks.startPolling,
  stopPolling: mocks.stopPolling,
  clearDetail: mocks.clearDetail,
  retry: mocks.retry,
  remove: mocks.remove,
});

vi.mock('@/stores/document', () => ({
  useDocumentStore: () => documentStore,
}));

function mountPage() {
  return mount(DocumentDetailPage, {
    global: {
      stubs: {
        MainLayout: { template: '<main><slot /></main>' },
        LoadingSpinner: { template: '<div />' },
        ErrorMessage: {
          props: ['message', 'code', 'traceId'],
          template: '<div class="action-error">{{ message }} Code: {{ code }} Trace ID: {{ traceId }}</div>',
        },
        DocumentStatusBadge: { template: '<div />' },
        ChunkViewer: { template: '<div />' },
        'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' },
        'el-alert': { template: '<div />' },
        'el-card': { template: '<section><slot /></section>' },
        'el-collapse': { template: '<section><slot /></section>' },
        'el-collapse-item': { template: '<section><slot /></section>' },
      },
    },
  });
}

describe('DocumentDetailPage action errors', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    documentStore.currentDocument = failedDocument;
    documentStore.detailLoading = false;
    documentStore.detailError = null;
    documentStore.actionError = null;
    mocks.fetchDetail.mockResolvedValue(failedDocument);
    mocks.confirm.mockResolvedValue(undefined);
  });

  it('shows retry failure details and keeps the document detail visible', async () => {
    mocks.retry.mockImplementation(async () => {
      documentStore.actionError = { message: 'Retry unavailable', code: 4008, traceId: 'retry-trace' };
      throw new Error('Retry unavailable');
    });
    const wrapper = mountPage();
    await wrapper.vm.$nextTick();
    await wrapper.findAll('button').find((button) => button.text() === 'Retry')!.trigger('click');
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).toContain('Retry unavailable');
    expect(wrapper.text()).toContain('Code: 4008');
    expect(wrapper.text()).toContain('Trace ID: retry-trace');
    expect(mocks.push).not.toHaveBeenCalled();
    expect(mocks.startPolling).not.toHaveBeenCalled();
  });

  it('shows delete failure details and does not navigate away', async () => {
    mocks.remove.mockImplementation(async () => {
      documentStore.actionError = { message: 'Delete denied', code: 3004, traceId: 'delete-trace' };
      throw new Error('Delete denied');
    });
    const wrapper = mountPage();
    await wrapper.vm.$nextTick();
    await wrapper.findAll('button').find((button) => button.text() === 'Delete')!.trigger('click');
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).toContain('Delete denied');
    expect(wrapper.text()).toContain('Code: 3004');
    expect(wrapper.text()).toContain('Trace ID: delete-trace');
    expect(mocks.push).not.toHaveBeenCalled();
  });

  it('clears a stale action error when a new retry starts', async () => {
    documentStore.actionError = { message: 'Old failure', code: 4008, traceId: 'old-trace' };
    mocks.retry.mockImplementation(async () => {
      documentStore.actionError = null;
    });
    const wrapper = mountPage();
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toContain('Old failure');

    await wrapper.findAll('button').find((button) => button.text() === 'Retry')!.trigger('click');
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).not.toContain('Old failure');
  });
});
