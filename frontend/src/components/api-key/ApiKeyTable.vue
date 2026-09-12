<template>
  <el-table :data="items" class="ak-table" v-loading="loading">
    <el-table-column prop="name" label="Name" min-width="180">
      <template #default="scope">
        <div class="ak-table__name">
          <span>{{ scope.row.name }}</span>
          <el-tag
            v-if="scope.row.effectiveStatus === 'EXPIRED'"
            type="info"
            size="small"
          >
            Expired
          </el-tag>
        </div>
      </template>
    </el-table-column>
    <el-table-column prop="keyPrefix" label="Key Prefix" min-width="160">
      <template #default="scope">
        <code class="ak-table__prefix">{{ scope.row.keyPrefix }}</code>
      </template>
    </el-table-column>
    <el-table-column prop="scope" label="Scope" width="100">
      <template #default="scope">{{ scope.row.scope }}</template>
    </el-table-column>
    <el-table-column label="Status" width="110">
      <template #default="scope">
        <el-tag :type="statusTag(scope.row.effectiveStatus)">
          {{ scope.row.effectiveStatus }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="Created" width="160">
      <template #default="scope">{{ formatDate(scope.row.createdAt) }}</template>
    </el-table-column>
    <el-table-column label="Last Used" width="160">
      <template #default="scope">
        {{ scope.row.lastUsedAt ? formatDate(scope.row.lastUsedAt) : '—' }}
      </template>
    </el-table-column>
    <el-table-column label="Actions" width="120" fixed="right">
      <template #default="scope">
        <el-button
          text
          type="danger"
          :disabled="isRevoked(scope.row) || revokingId === scope.row.id"
          :data-testid="`${dataId}-revoke-${scope.row.id}`"
          @click="confirmRevoke(scope.row)"
        >
          {{ revokingId === scope.row.id ? 'Revoking…' : 'Revoke' }}
        </el-button>
      </template>
    </el-table-column>
  </el-table>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import type { ApiKey } from '@/types/apiKey';

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
      `Revoke API key "${key.name}" (${key.keyPrefix})? Requests using it will stop being accepted.`,
      'Revoke API key',
      { confirmButtonText: 'Revoke', cancelButtonText: 'Cancel', type: 'warning' }
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
  }
  &__prefix {
    font-family: 'SFMono-Regular', Consolas, monospace;
    font-size: 12px;
    color: #606266;
    word-break: break-all;
  }
}
</style>