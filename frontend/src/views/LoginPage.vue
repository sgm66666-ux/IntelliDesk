<template>
  <div class="login-page">
    <div class="login-page__card">
      <h1 class="login-page__title">IntelliDesk</h1>
      <el-tabs v-model="activeTab" class="login-page__tabs">
        <el-tab-pane label="Login" name="login">
          <el-form @submit.prevent="handleLogin" label-position="top">
            <el-form-item label="Username">
              <el-input v-model="loginForm.username" placeholder="Enter username" />
            </el-form-item>
            <el-form-item label="Password">
              <el-input v-model="loginForm.password" type="password" placeholder="Enter password" show-password />
            </el-form-item>
            <el-alert v-if="loginError" :title="loginError" type="error" show-icon :closable="false" style="margin-bottom: 16px" />
            <el-button type="primary" native-type="submit" :loading="auth.loading" style="width: 100%">
              Login
            </el-button>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="Register" name="register">
          <el-form @submit.prevent="handleRegister" label-position="top">
            <el-form-item label="Username">
              <el-input v-model="registerForm.username" placeholder="Choose a username" />
            </el-form-item>
            <el-form-item label="Password">
              <el-input v-model="registerForm.password" type="password" placeholder="Choose a password" show-password />
            </el-form-item>
            <el-alert v-if="registerError" :title="registerError" type="error" show-icon :closable="false" style="margin-bottom: 16px" />
            <el-button type="primary" native-type="submit" :loading="auth.loading" style="width: 100%">
              Register
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();
const router = useRouter();

const activeTab = ref('login');
const loginForm = reactive({ username: '', password: '' });
const registerForm = reactive({ username: '', password: '' });
const loginError = ref('');
const registerError = ref('');

async function handleLogin() {
  loginError.value = '';
  try {
    await auth.loginAction({ username: loginForm.username, password: loginForm.password });
    router.push('/workspaces');
  } catch (e: any) {
    loginError.value = e.message || 'Login failed';
  }
}

async function handleRegister() {
  registerError.value = '';
  try {
    await auth.registerAction({ username: registerForm.username, password: registerForm.password });
    router.push('/workspaces');
  } catch (e: any) {
    registerError.value = e.message || 'Register failed';
  }
}
</script>

<style scoped lang="scss">
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;

  &__card {
    width: 400px;
    padding: 32px;
    background: #fff;
    border-radius: 8px;
    box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  }

  &__title {
    text-align: center;
    font-size: 28px;
    font-weight: 700;
    color: #409eff;
    margin-bottom: 24px;
  }

  &__tabs {
    :deep(.el-tabs__header) {
      margin-bottom: 16px;
    }
  }
}
</style>