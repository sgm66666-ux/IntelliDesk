import { createRouter, createWebHistory, type RouterHistory } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

export function createAppRouter(history: RouterHistory = createWebHistory()) {
  const router = createRouter({
    history,
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginPage.vue'),
      meta: { guest: true },
    },
    {
      path: '/workspaces',
      name: 'workspaces',
      component: () => import('@/views/WorkspaceListPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/workspaces/:workspaceId',
      name: 'workspace-detail',
      component: () => import('@/views/WorkspaceShellPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/workspaces/:workspaceId/knowledge-bases',
      name: 'workspace-knowledge-bases',
      component: () => import('@/views/KnowledgeBaseListPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/workspaces/:workspaceId/knowledge-bases/:knowledgeBaseId',
      name: 'knowledge-base-detail',
      component: () => import('@/views/KnowledgeBaseDetailPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/workspaces/:workspaceId/knowledge-bases/:knowledgeBaseId/documents/:documentId',
      name: 'document-detail',
      component: () => import('@/views/DocumentDetailPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/workspaces/:workspaceId/conversations',
      name: 'workspace-conversations',
      component: () => import('@/views/ConversationListPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/workspaces/:workspaceId/conversations/:conversationId',
      name: 'workspace-conversation-chat',
      component: () => import('@/views/ChatPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/workspaces/:workspaceId/api-keys',
      name: 'workspace-api-keys',
      component: () => import('@/views/ApiKeyPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/',
      redirect: '/workspaces',
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/workspaces',
    },
  ],
});

  router.beforeEach(async (to) => {
    const auth = useAuthStore();

    if (!auth.bootstrapReady) {
      await auth.bootstrapAuth();
    }

    if (to.meta.requiresAuth && !auth.authenticated) {
      return '/login';
    }
    if (to.meta.guest && auth.authenticated) {
      return '/workspaces';
    }
    return true;
  });

  return router;
}

const router = createAppRouter();

export default router;
