<template>
  <div class="login-page">
    <div class="login-page__grid" aria-hidden="true" />
    <section class="login-page__intro">
      <BrandMark />
      <div class="login-page__pitch">
        <span class="login-page__eyebrow">Enterprise Knowledge Base · RAG · Agent</span>
        <h1>让企业知识真正<br />成为智能生产力</h1>
        <p>基于 RAG、混合检索与工具调用，<br />构建可追溯的企业智能问答体验。</p>
      </div>
      <div class="login-page__trust">
        <span>可追溯引用</span><span>混合检索</span><span>Agent 工具调用</span>
      </div>
    </section>

    <main class="login-page__main">
      <div class="login-page__mobile-brand"><BrandMark /></div>
      <div class="login-page__card">
        <header class="login-page__card-header">
          <h2>{{ activeTab === 'login' ? '欢迎回来' : '注册账号' }}</h2>
          <p>{{ activeTab === 'login' ? '登录后进入 IntelliDesk 工作台' : '创建账号，开始构建企业知识库' }}</p>
        </header>
        <el-tabs v-model="activeTab" class="login-page__tabs" stretch>
          <el-tab-pane label="登录" name="login">
            <el-form @submit.prevent="handleLogin" label-position="top">
              <el-form-item label="用户名">
                <el-input v-model="loginForm.username" placeholder="请输入用户名" size="large" />
              </el-form-item>
              <el-form-item label="密码">
                <el-input v-model="loginForm.password" type="password" placeholder="请输入密码" show-password size="large" />
              </el-form-item>
              <el-alert v-if="loginError" :title="loginError" type="error" show-icon :closable="false" class="login-page__alert" />
              <el-button type="primary" native-type="submit" :loading="auth.loading" size="large" class="login-page__submit">登录</el-button>
            </el-form>
          </el-tab-pane>
          <el-tab-pane label="注册" name="register">
            <el-form @submit.prevent="handleRegister" label-position="top">
              <el-form-item label="用户名">
                <el-input v-model="registerForm.username" placeholder="设置用户名" size="large" />
              </el-form-item>
              <el-form-item label="密码">
                <el-input v-model="registerForm.password" type="password" placeholder="设置密码" show-password size="large" />
              </el-form-item>
              <el-alert v-if="registerError" :title="registerError" type="error" show-icon :closable="false" class="login-page__alert" />
              <el-button type="primary" native-type="submit" :loading="auth.loading" size="large" class="login-page__submit">注册账号</el-button>
            </el-form>
          </el-tab-pane>
        </el-tabs>
        <p class="login-page__footnote">企业知识库与智能 Agent 平台</p>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import BrandMark from '@/components/BrandMark.vue';

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
    loginError.value = e.message || '登录失败，请检查用户名和密码';
  }
}

async function handleRegister() {
  registerError.value = '';
  try {
    await auth.registerAction({ username: registerForm.username, password: registerForm.password });
    router.push('/workspaces');
  } catch (e: any) {
    registerError.value = e.message || '注册失败，请稍后重试';
  }
}
</script>

<style scoped lang="scss">
.login-page {
  position: relative;
  display: grid;
  grid-template-columns: minmax(420px, 1.05fr) minmax(480px, 0.95fr);
  min-height: 100dvh;
  overflow: hidden;
  background: #f8f9fb;

  &__grid {
    position: absolute;
    inset: 0 50% 0 0;
    pointer-events: none;
    background-image:
      linear-gradient(rgba(148, 163, 184, 0.09) 1px, transparent 1px),
      linear-gradient(90deg, rgba(148, 163, 184, 0.09) 1px, transparent 1px),
      radial-gradient(circle at 35% 38%, rgba(37, 99, 235, 0.09), transparent 38%);
    background-size: 36px 36px, 36px 36px, auto;
    mask-image: linear-gradient(to right, #000 40%, transparent 100%);
  }

  &__intro {
    position: relative;
    z-index: 1;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    padding: 48px clamp(48px, 7vw, 104px);
  }

  &__pitch { margin: auto 0; }
  &__eyebrow { color: var(--id-accent); font-size: 12px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; }
  h1 { margin: 22px 0 20px; color: #0f172a; font-size: clamp(38px, 4.2vw, 58px); font-weight: 700; line-height: 1.16; letter-spacing: -0.055em; }
  &__pitch p { color: #596579; font-size: 16px; line-height: 1.85; }

  &__trust {
    display: flex;
    flex-wrap: wrap;
    gap: 18px;
    color: #788398;
    font-size: 12px;
    span::before { content: '✓'; margin-right: 6px; color: var(--id-success); font-weight: 700; }
  }

  &__main { position: relative; z-index: 2; display: grid; place-items: center; padding: 40px; border-left: 1px solid rgba(222, 226, 232, 0.85); background: rgba(255, 255, 255, 0.84); }
  &__mobile-brand { display: none; }
  &__card { width: min(100%, 420px); padding: 36px; border: 1px solid var(--id-border); border-radius: 18px; background: #fff; box-shadow: var(--id-shadow-card); }
  &__card-header {
    margin-bottom: 22px;
    h2 { margin: 0; color: var(--id-text); font-size: 24px; font-weight: 680; letter-spacing: -0.03em; }
    p { margin: 8px 0 0; color: var(--id-text-muted); font-size: 13px; }
  }

  &__tabs :deep(.el-tabs__header) { margin-bottom: 24px; }
  &__tabs :deep(.el-tabs__item) { color: var(--id-text-muted); font-weight: 560; }
  &__tabs :deep(.el-tabs__item.is-active) { color: var(--id-text); }
  &__tabs :deep(.el-tabs__active-bar) { background: var(--id-text); }
  &__tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background: var(--id-border); }
  &__tabs :deep(.el-form-item__label) { color: var(--id-text-secondary); font-size: 13px; font-weight: 560; }
  &__alert { margin-bottom: 16px; }
  &__submit { width: 100%; margin-top: 4px; }
  &__footnote { margin: 20px 0 0; color: #a0a8b5; font-size: 11px; text-align: center; }
}

@media (max-width: 900px) {
  .login-page {
    display: block;
    padding: 32px 20px;
    overflow-y: auto;
    background-image: linear-gradient(rgba(148, 163, 184, 0.08) 1px, transparent 1px), linear-gradient(90deg, rgba(148, 163, 184, 0.08) 1px, transparent 1px);
    background-size: 32px 32px;
    &__grid, &__intro { display: none; }
    &__main { display: flex; min-height: calc(100dvh - 64px); flex-direction: column; justify-content: center; gap: 28px; padding: 0; border: 0; background: transparent; }
    &__mobile-brand { display: block; }
  }
}

@media (max-width: 480px) { .login-page__card { padding: 28px 22px; } }
</style>
