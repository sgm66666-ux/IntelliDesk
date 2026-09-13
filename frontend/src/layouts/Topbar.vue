<template>
  <header class="topbar">
    <div class="topbar__left">
      <BrandMark />
      <span class="topbar__divider" />
      <span class="topbar__product">企业知识工作台</span>
    </div>
    <div class="topbar__right">
      <el-dropdown trigger="click">
        <span class="topbar__user">
          <span class="topbar__avatar">{{ userInitial }}</span>
          <span class="topbar__username">{{ auth.user?.username }}</span>
          <el-icon><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="handleLogout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<script setup lang="ts">
import { ArrowDown } from '@element-plus/icons-vue';
import { computed } from 'vue';
import { useAuthStore } from '@/stores/auth';
import { useRouter } from 'vue-router';
import BrandMark from '@/components/BrandMark.vue';

const auth = useAuthStore();
const router = useRouter();
const userInitial = computed(() => auth.user?.username?.trim().slice(0, 1).toUpperCase() || 'U');

async function handleLogout() {
  await auth.logoutAction();
  router.push('/login');
}
</script>

<style scoped lang="scss">
.topbar {
  height: 64px;
  background: rgba(255, 255, 255, 0.96);
  border-bottom: 1px solid var(--id-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 22px;
  flex-shrink: 0;

  &__left { display: flex; align-items: center; }
  &__divider { width: 1px; height: 18px; margin: 0 14px; background: var(--id-border); }
  &__product { color: var(--id-text-muted); font-size: 12px; }

  &__user {
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 8px;
    min-height: 36px;
    padding: 3px 7px 3px 4px;
    border-radius: 9px;
    color: var(--id-text-secondary);
    font-size: 13px;

    &:hover { background: #f5f6f8; }
  }

  &__avatar {
    display: grid;
    width: 28px;
    height: 28px;
    place-items: center;
    border: 1px solid #cdd9f8;
    border-radius: 8px;
    background: var(--id-accent-soft);
    color: var(--id-accent);
    font-size: 12px;
    font-weight: 700;
  }
}

@media (max-width: 640px) {
  .topbar {
    padding: 0 14px;
    &__divider, &__product, &__username { display: none; }
  }
}
</style>
