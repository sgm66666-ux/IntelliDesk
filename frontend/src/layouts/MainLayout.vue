<template>
  <div class="main-layout">
    <Topbar />
    <div class="main-layout__body">
      <Sidebar />
      <main class="main-layout__content">
        <slot>
          <router-view />
        </slot>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import Topbar from './Topbar.vue';
import Sidebar from './Sidebar.vue';
</script>

<style scoped lang="scss">
.main-layout {
  height: 100vh;
  display: flex;
  flex-direction: column;

  &__body {
    display: flex;
    flex: 1;
    overflow: hidden;
  }

  &__content {
    flex: 1;
    overflow-y: auto;
    background: #f5f7fa;
  }
}
</style>