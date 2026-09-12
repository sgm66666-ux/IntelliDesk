import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'path';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    // Exclude Playwright E2E specs (e2e/*.spec.ts are run by `npm run test:e2e`,
    // not by the vitest unit runner). Prevents vitest from picking up phase7.spec.ts.
    exclude: ['**/node_modules/**', '**/dist/**', '**/e2e/**'],
  },
});