export const documentStatusLabels: Record<string, string> = {
  UPLOADING: '上传中',
  PENDING: '等待处理',
  PROCESSING: '处理中',
  COMPLETED: '已完成',
  FAILED: '失败',
  DELETING: '删除中',
  READY: '就绪',
};

export const apiKeyStatusLabels: Record<string, string> = {
  ACTIVE: '启用',
  EXPIRED: '已过期',
  REVOKED: '已撤销',
  FAILED: '失败',
};

export const commonStatusLabels: Record<string, string> = {
  READY: '就绪',
  COMPLETED: '已完成',
  ACTIVE: '启用',
  FAILED: '失败',
  PROCESSING: '处理中',
};

export function documentStatusLabel(status?: string | null) {
  return documentStatusLabels[status || ''] || '未知状态';
}

export function apiKeyStatusLabel(status?: string | null) {
  return apiKeyStatusLabels[status || ''] || status || '未知状态';
}

export function commonStatusLabel(status?: string | null) {
  return commonStatusLabels[status || ''] || status || '未知状态';
}
