<template>
  <el-table :data="items" class="ak-table" v-loading="loading">
    <el-table-column prop="name" label="名称" min-width="180">
      <template #default="scope">
        <div class="ak-table__name">
          <span>{{ scope.row.name }}</span>
          <el-tag
            v-if="scope.row.effectiveStatus === 'EXPIRED'"
            type="info"
            size="small"
          >
            已过期
          </el-tag>
        </div>
      </template>
    </el-table-column>
    <el-table-column prop="keyPrefix" label="Key 前缀" min-width="170">
      <template #default="scope">
        <code class="ak-table__prefix">{{ scope.row.keyPrefix }}</code>
      </template>
    </el-table-column>
    <el-table-column prop="scope" label="Scope" width="110">
      <template #default="scope"><span class="ak-table__scope">{{ scope.row.scope }}</span></template>
    </el-table-column>
    <el-table-column label="状态" width="110">
      <template #default="scope">
        <el-tag :type="statusTag(scope.row.effectiveStatus)">
          {{ apiKeyStatusLabel(scope.row.effectiveStatus) }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="创建时间" width="170">
      <template #default="scope">{{ formatDate(scope.row.createdAt) }}</template>
    </el-table-column>
    <el-table-column label="最近使用" width="170">
      <template #default="scope">
        {{ scope.row.lastUsedAt ? formatDate(scope.row.lastUsedAt) : '—' }}
      </template>
    </el-table-column>
    <el-table-column label="操作" width="120" fixed="right">
      <template #default="scope">
        <el-button
          text
          type="danger"
          :disabled="isRevoked(scope.row) || revokingId === scope.row.id"
          :data-testid="`${dataId}-revoke-${scope.row.id}`"
          @click="confirmRevoke(scope.row)"
        >
          {{ revokingId === scope.row.id ? '撤销中…' : '撤销' }}
        </el-button>
      </template>
    </el-table-column>
  </el-table>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import type { ApiKey } from '@/types/apiKey';
import { apiKeyStatusLabel } from '@/lib/display';

const props = withDefaults(
  defineProps<{
    items: ApiKey[];
    loading?: boolean;
    revokingId?: number | null;
    dataId?: string;
  }>(),
  { loading: false, revokingId: null, dataId: 'api-key' }
);

const emit = defineEmits<{
  (e: 'revoke', key: ApiKey): void;
}>();

function isRevoked(key: ApiKey) {
  return key.effectiveStatus === 'REVOKED' || key.status === 'REVOKED';
}

function statusTag(status: string) {
  if (status === 'ACTIVE') return 'success';
  if (status === 'EXPIRED') return 'info';
  return 'danger';
}

function formatDate(value?: string) {
  if (!value) return '';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? value : d.toLocaleString();
}

async function confirmRevoke(key: ApiKey) {
  if (isRevoked(key) || props.revokingId === key.id) return;
  try {
    await ElMessageBox.confirm(
      `确认撤销 API Key“${key.name}”（${key.keyPrefix}）吗？使用该凭证的请求将不再被接受。`,
      '撤销 API Key',
      { confirmButtonText: '撤销', cancelButtonText: '取消', type: 'warning' }
    );
    emit('revoke', key);
  } catch {
    /* cancelled */
  }
}
</script>

<style scoped lang="scss">
.ak-table {
  &__name {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--id-text);
    font-weight: 620;
  }
  &__prefix {
    font-family: 'SFMono-Regular', Consolas, monospace;
    font-size: 12px;
    display: inline-flex;
    padding: 5px 8px;
    border: 1px solid var(--id-border);
    border-radius: 6px;
    background: #f7f8fa;
    color: #475467;
    word-break: break-all;
  }
  &__scope { display: inline-flex; padding: 4px 8px; border-radius: 6px; background: #f2f4f7; color: #475467; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 11px; }
}
</style>
