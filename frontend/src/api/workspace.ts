import client from './client';
import type { Workspace, CreateWorkspaceRequest } from '@/types/api';

export function listWorkspaces(): Promise<Workspace[]> {
  return client.get('/workspaces');
}

export function getWorkspace(id: number): Promise<Workspace> {
  return client.get(`/workspaces/${id}`);
}

export function createWorkspace(data: CreateWorkspaceRequest): Promise<Workspace> {
  return client.post('/workspaces', data);
}

export function updateWorkspace(id: number, data: Partial<CreateWorkspaceRequest>): Promise<Workspace> {
  return client.put(`/workspaces/${id}`, data);
}

export function deleteWorkspace(id: number): Promise<void> {
  return client.delete(`/workspaces/${id}`);
}