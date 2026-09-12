import { defineConfig, devices } from '@playwright/test';

/**
 * IntelliDesk Phase 7 Wave 5 — final deterministic Playwright E2E.
 *
 * baseURL is http://localhost (the production-style stack: web/Nginx :80 -> backend
 * container :8080 -> real PostgreSQL/Redis/RabbitMQ/MinIO/Elasticsearch). The AI
 * provider is the E2E-only deterministic stub (deploy/provider-stub) reached over
 * the Docker network. No browser API mocking, no fake SSE.
 *
 * The stack must already be running:
 *   docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 20_000 },
  use: {
    baseURL: 'http://localhost',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});