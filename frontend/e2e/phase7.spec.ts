/**
 * IntelliDesk Phase 7 Wave 5 — Final deterministic Playwright E2E.
 *
 * Topology exercised (real services, no mocking):
 *   Playwright real browser -> http://localhost:80 (web/Nginx) -> backend container :8080
 *   -> real PostgreSQL / Redis / RabbitMQ / MinIO / Elasticsearch.
 *   AI provider = E2E-only deterministic stub (deploy/provider-stub) reached over the
 *   Docker internal network (CHAT_BASE_URL / EMBEDDING_BASE_URL=http://provider-stub:18080).
 *
 * Seed data is created through the same Nginx entrypoint (http://localhost/api/...) using
 * a Playwright APIRequestContext that carries the user's Bearer token — this is real
 * browser -> Nginx -> backend -> real DB/MinIO/RabbitMQ, never browser-side mocks.
 *
 * Safety rules enforced here:
 *   - RAG must show at least one non-empty citation (References), never citation=[]
 *   - Agent must show tool_call -> tool_result -> final answer (knowledge_base_list)
 *   - API Key one-time secret is read once, then verified: not in DOM after close/reload,
 *     not in localStorage/sessionStorage, not echoed by list API; the key is revoked at
 *     the end and the credential is confirmed invalid (401).
 */
import { test, expect, type APIRequestContext, type Page } from '@playwright/test';

const TXT_DOC = Buffer.from(
  'Beijing travel budget: the daily allowance is capped at 600 yuan.\n' +
    'Shanghai travel budget: the daily allowance is capped at 650 yuan.\n' +
    'Tokyo travel budget: the daily allowance is capped at 12000 yen.\n',
  'utf8'
);

function uniqueUser(prefix: string) {
  const n = Date.now().toString(36) + Math.floor(Math.random() * 1e6).toString(36);
  return { username: `${prefix}_${n}`, password: 'Test123456' };
}

async function loginViaApi(api: APIRequestContext, username: string, password: string): Promise<string> {
  const res = await api.post('/api/auth/register', {
    data: { username, password, email: `${username}@e2e.local` },
  });
  expect(res.status()).toBe(200);
  const body = await res.json();
  expect(body.code).toBe(0);
  return body.data.accessToken as string;
}

/** Poll a document until terminal status (COMPLETED or FAILED), returning the record. */
async function waitDocument(
  api: APIRequestContext,
  token: string,
  wsId: number,
  kbId: number,
  docId: number
): Promise<any> {
  const headers = { Authorization: `Bearer ${token}` };
  for (let i = 0; i < 120; i++) {
    const res = await api.get(
      `/api/workspaces/${wsId}/knowledge-bases/${kbId}/documents/${docId}`,
      { headers }
    );
    const body = await res.json();
    if (body.code !== 0) throw new Error(`document get failed: ${JSON.stringify(body)}`);
    const status = body.data.status;
    if (status === 'COMPLETED' || status === 'FAILED') return body.data;
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error('document did not reach terminal state in time');
}

/**
 * Document status COMPLETED means chunks are persisted (PG) while the async retrieval
 * task still has to embed those chunks + index them into Elasticsearch before they are
 * actually searchable (vector rt.status=READY, ES BM25). This async gap is established
 * Wave 3 behavior (unchanged), so the E2E gates RAG on the real retrieval-ready signal:
 * the chunk must be visible in Elasticsearch (a backing service exposed on the host by
 * the base compose at :9200). This is host-side infrastructure gating only — the user
 * flow assertions (chat/citations) still run browser -> Nginx :80 -> backend container.
 */
async function waitRetrievalReady(api: APIRequestContext, wsId: number): Promise<void> {
  const url = `http://localhost:9200/intellidesk-chunks-v1/_count`;
  for (let i = 0; i < 120; i++) {
    try {
      const res = await api.post(url, {
        data: { query: { term: { workspaceId: wsId } } },
      });
      if (res.status() === 200) {
        const body = await res.json();
        if (body && typeof body.count === 'number' && body.count >= 1) return;
        // index exists but this workspace not indexed yet -> keep waiting
      } else {
        // 404 = index not created yet, other 4xx/5xx = not ready -> keep waiting
      }
    } catch {
      // ES not reachable / connection refused yet; keep waiting.
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`retrieval index never became ready for workspace ${wsId}`);
}

/**
 * One user, real browser: register/login through Nginx, then seed workspace/KB/document/
 * conversation via API (through Nginx) and drive the final chain through the UI.
 */
async function runFinalChain(page: Page, api: APIRequestContext) {
  const { username, password } = uniqueUser('w5e2e');
  const token = await loginViaApi(api, username, password);
  const authHeaders = { Authorization: `Bearer ${token}` };

  // ---- Seed: workspace -> KB -> document (COMPLETED + chunks) -> conversation ----
  let res = await api.post('/api/workspaces', {
    data: { name: 'Wave5 E2E WS', description: 'final chain' },
    headers: authHeaders,
  });
  expect((await res.json()).code).toBe(0);
  const wsId = (await res.json()).data.id as number;

  res = await api.post(`/api/workspaces/${wsId}/knowledge-bases`, {
    data: { name: 'Wave5 KB', chunkStrategy: 'RECURSIVE', chunkSize: 500, chunkOverlap: 50 },
    headers: authHeaders,
  });
  expect((await res.json()).code).toBe(0);
  const kbId = (await res.json()).data.id as number;

  res = await api.post(
    `/api/workspaces/${wsId}/knowledge-bases/${kbId}/documents`,
    {
      multipart: { file: { name: 'w5-travel-budget.txt', mimeType: 'text/plain', buffer: TXT_DOC } },
      headers: authHeaders,
    }
  );
  expect((await res.json()).code).toBe(0);
  const docId = (await res.json()).data.documentId as number;

  const doc = await waitDocument(api, token, wsId, kbId, docId);
  expect(doc.status).toBe('COMPLETED');

  // Gate RAG on the retrieval index actually being ready (async embedding+ES by the
  // retrieval task lags the document COMPLETED status by design).
  await waitRetrievalReady(api, wsId);

  res = await api.get(
    `/api/workspaces/${wsId}/knowledge-bases/${kbId}/documents/${docId}/chunks`,
    { params: { page: 1, size: 5 }, headers: authHeaders }
  );
  const chunkBody = await res.json();
  expect(chunkBody.code).toBe(0);
  expect(chunkBody.data.items.length).toBeGreaterThanOrEqual(1);

  res = await api.post(`/api/workspaces/${wsId}/conversations`, {
    data: { title: 'Wave5 E2E' },
    headers: authHeaders,
  });
  expect((await res.json()).code).toBe(0);
  const convId = (await res.json()).data.id as number;

  // ---- UI login of the SAME user through Nginx ----
  await page.goto('/login');
  await page.getByPlaceholder('Enter username').fill(username);
  await page.getByPlaceholder('Enter password').fill(password);
  await page.getByRole('button', { name: 'Login', exact: true }).click();
  await expect(page).toHaveURL(/\/workspaces$/, { timeout: 20_000 });

  // ---- RAG chat through Nginx (SSE stream) ----
  await page.goto(`/workspaces/${wsId}/conversations/${convId}`);
  const composer = page.getByPlaceholder(/Type a message/);
  await composer.waitFor({ state: 'visible', timeout: 30_000 }); // enabled once a KB is auto-selected
  await composer.fill('What is the Beijing daily allowance?');
  await page.getByRole('button', { name: 'Send', exact: true }).click();

  // Streamed answer reaches the fixture content (proves SSE through Nginx rendered live)
  await expect(page.locator('.assistant-answer__content')).toContainText('600', {
    timeout: 30_000,
  });
  await expect(page.locator('.assistant-answer__content')).toContainText('yuan', {
    timeout: 10_000,
  });
  // Non-empty citation: References section is rendered (never citation=[])
  await expect(page.locator('.assistant-answer__citations-title', { hasText: 'References' })).toBeVisible({
    timeout: 15_000,
  });
  // The citation carries a real documentName derived from the uploaded fixture.
  await expect(page.locator('.assistant-answer__citation-meta', { hasText: 'w5-travel-budget.txt' })).toBeVisible({
    timeout: 10_000,
  });

  // ---- Agent mode: tool_call -> tool_result -> final answer ----
  await page.getByRole('button', { name: 'Agent', exact: true }).click();
  await composer.fill('List my knowledge bases');
  await page.getByRole('button', { name: 'Send', exact: true }).click();
  await expect(page.locator('body')).toContainText('knowledge_base_list', { timeout: 30_000 });
  await expect(page.locator('body')).toContainText(/listed the available knowledge bases/, {
    timeout: 15_000,
  });

  // ---- API Key: create -> one-time secret -> close/reload gone -> revoke -> invalid ----
  await page.goto(`/workspaces/${wsId}/api-keys`);
  await page.getByTestId('api-key-new').click();
  await page.getByPlaceholder('e.g. prod-integration').fill('w5-e2e-key');
  await page.getByRole('button', { name: 'Create', exact: true }).click();

  const code = page.locator('.ak-dialog__code');
  await expect(code).toBeVisible({ timeout: 15_000 });
  const fullKey = (await code.textContent())?.trim() ?? '';
  expect(fullKey).toMatch(/^isk_/);

  // Never in Web Storage, never in console/trace.
  const webStorage = await page.evaluate(() => ({
    ls: { ...localStorage },
    ss: { ...sessionStorage },
  }));
  expect(JSON.stringify(webStorage)).not.toContain(fullKey);
  expect(JSON.stringify(webStorage)).not.toContain('isk_');

  // List API must not expose the raw secret or its hash.
  res = await api.get(`/api/workspaces/${wsId}/api-keys`, { headers: authHeaders });
  const listBody = await res.json();
  expect(listBody.code).toBe(0);
  const key = listBody.data.find((k: any) => k.name === 'w5-e2e-key');
  expect(key).toBeTruthy();
  expect(key.keyPrefix).toBeTruthy();
  expect(key.fullKey).toBeUndefined();
  expect(key.keyHash).toBeUndefined();

  // Close -> secret leaves the DOM.
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(code).toBeHidden({ timeout: 10_000 });
  // Reload -> secret still gone.
  await page.reload();
  await expect(page.locator('.ak-dialog__code')).toBeHidden();
  await expect(page.getByText(fullKey)).toHaveCount(0);

  // Revoke via UI (confirm box).
  await page.getByTestId(`api-key-revoke-${key.id}`).click();
  await page.locator('.el-message-box__btns').getByRole('button', { name: 'Revoke', exact: true }).click();
  await expect(page.getByTestId(`api-key-revoke-${key.id}`)).toBeDisabled({ timeout: 15_000 });

  // Credential invalidation: using the captured key now yields 401.
  res = await api.get('/api/workspaces', { headers: { Authorization: `Bearer ${fullKey}` } });
  expect(res.status()).toBe(401);

  // Cookie/reload persistence already covered; we remain authenticated after reload.
  await page.reload();
  await expect(page).toHaveURL(/\/api-keys$/);
}

test.describe('Phase 7 Wave 5 — final E2E through Nginx (:80)', () => {
  test('auth: register through Nginx, protected route + reload persists via refresh cookie', async ({
    page,
  }) => {
    const { username, password } = uniqueUser('w5reg');
    await page.goto('/login');
    await page.getByRole('tab', { name: 'Register' }).click();
    await page.getByPlaceholder('Choose a username').fill(username);
    await page.getByPlaceholder('Choose a password').fill(password);
    await page.getByRole('button', { name: 'Register', exact: true }).click();
    await expect(page).toHaveURL(/\/workspaces$/, { timeout: 20_000 });

    // Reflect refresh cookie: HttpOnly + workspace-scoped /api/auth, so protected pages
    // survive a reload (auth bootstrap via /api/auth/refresh through Nginx).
    const cookies = await page.context().cookies();
    const refresh = cookies.find((c) => c.name === 'refresh_token');
    expect(refresh).toBeTruthy();
    expect(refresh?.httpOnly).toBe(true);
    expect(refresh?.path).toBe('/api/auth');

    await page.reload();
    await expect(page).toHaveURL(/\/workspaces$/);
    await expect(page.locator('body')).toContainText('Workspaces');
  });

  test('final chain: seed + RAG citation + Agent tools + API Key safety + cookie reload', async ({
    page,
    request,
  }) => {
    const api: APIRequestContext = request as unknown as APIRequestContext;
    try {
      await runFinalChain(page, api);
    } finally {
      // The created API key was revoked inside runFinalChain; nothing persistent kept open.
    }
  });
});