import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import vm from 'node:vm';
// Execute the actual script logic with k6 imports stubbed; never send HTTP.
const source = readFileSync(new URL('./non_llm_http.js', import.meta.url), 'utf8')
  .replace(/^import .*;$/gm, '')
  .replace('export const options', 'const options')
  .replace('export default function ()', 'function iteration()')
  + '\nglobalThis.output = { options, iteration };';
function load(extra = {}) {
  const requests = [];
  const ctx = { __ENV: { BACKEND_WORKSPACE_ID: '1', BACKEND_KB_ID: '2', BACKEND_ACCESS_TOKEN: 'synthetic-' + randomUUID(), ...extra },
    http: { get: (...args) => { requests.push(args); return { status: 200 }; } },
    check: (r, checks) => Object.values(checks).forEach(fn => assert.equal(fn(r), true)), sleep: () => {} };
  vm.runInNewContext(source, ctx);
  return { ...ctx.output, requests };
}
test('default is bounded read-only metadata, with percentiles and error metrics', () => {
  const run = load(); run.iteration();
  assert.equal(run.options.scenarios.backend_read.vus, 1);
  assert.equal(run.options.scenarios.backend_read.iterations, 10);
  assert.equal(run.options.discardResponseBodies, true);
  assert.ok(run.options.summaryTrendStats.includes('p(99)'));
  assert.equal(run.requests[0][0], 'http://127.0.0.1/api/workspaces/1/knowledge-bases/2');
  assert.equal(run.requests[0][1].redirects, 0);
});
test('only existing non-LLM list/status GET paths are generated', () => {
  const list = load({ BACKEND_TARGET: 'document-list' }); list.iteration();
  assert.match(list.requests[0][0], /documents\?page=1&size=20$/);
  const status = load({ BACKEND_TARGET: 'document-status', BACKEND_DOCUMENT_ID: '3' }); status.iteration();
  assert.match(status.requests[0][0], /documents\/3$/);
});
test('LLM routes, missing credentials, bad IDs, and unbounded load fail closed', () => {
  for (const invalid of [{ BACKEND_TARGET: 'chat' }, { BACKEND_ACCESS_TOKEN: '' }, { BACKEND_KB_ID: '../chat' },
    { BACKEND_VUS: '100' }, { BACKEND_ITERATIONS: '100000' }, { BACKEND_BASE_URL: 'ftp://invalid' }]) {
    assert.throws(() => load(invalid));
  }
});
