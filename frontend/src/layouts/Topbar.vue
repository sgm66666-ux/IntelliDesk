<template>
  <header class="topbar">
    <div class="topbar__left">
      <span class="topbar__logo">IntelliDesk</span>
    </div>
    <div class="topbar__right">
      <el-dropdown trigger="click">
        <span class="topbar__user">
          {{ auth.user?.username }}
          <el-icon><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="handleLogout">Logout</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<script setup lang="ts">
import { ArrowDown } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { useRouter } from 'vue-router';

const auth = useAuthStore();
const router = useRouter();

async function handleLogout() {
  await auth.logoutAction();
  router.push('/login');
}
</script>

<style scoped lang="scss">
.topbar {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  flex-shrink: 0;

  &__logo {
    font-size: 18px;
    font-weight: 700;
    color: #409eff;
  }

  &__user {
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 4px;
    color: #606266;
  }
}
</style>