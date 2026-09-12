import { defineStore } from 'pinia';
import { ref } from 'vue';
import * as workspaceApi from '@/api/workspace';
import type { Workspace, CreateWorkspaceRequest } from '@/types/api';

export const useWorkspaceStore = defineStore('workspace', () => {
  const workspaces = ref<Workspace[]>([]);
  const currentWorkspace = ref<Workspace | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function fetchList() {
    loading.value = true;
    error.value = null;
    try {
      workspaces.value = await workspaceApi.listWorkspaces();
    } catch (e: any) {
      error.value = e.message || 'Failed to load workspaces';
    } finally {
      loading.value = false;
    }
  }

  async function fetchDetail(id: number) {
    loading.value = true;
    error.value = null;
    try {
      currentWorkspace.value = await workspaceApi.getWorkspace(id);
    } catch (e: any) {
      error.value = e.message || 'Failed to load workspace';
    } finally {
      loading.value = false;
    }
  }

  async function create(data: CreateWorkspaceRequest) {
    loading.value = true;
    error.value = null;
    try {
      const ws = await workspaceApi.createWorkspace(data);
      workspaces.value.push(ws);
      return ws;
    } catch (e: any) {
      error.value = e.message || 'Failed to create workspace';
      throw e;
    } finally {
      loading.value = false;
    }
  }

  async function remove(id: number) {
    loading.value = true;
    error.value = null;
    try {
      await workspaceApi.deleteWorkspace(id);
      workspaces.value = workspaces.value.filter((w) => w.id !== id);
    } catch (e: any) {
      error.value = e.message || 'Failed to delete workspace';
      throw e;
    } finally {
      loading.value = false;
    }
  }

  return {
    workspaces,
    currentWorkspace,
    loading,
    error,
    fetchList,
    fetchDetail,
    create,
    remove,
  };
});