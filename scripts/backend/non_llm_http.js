import http from 'k6/http';
import { check, sleep } from 'k6';

// Preparation only. Never invoked by Maven; no formal run-set or LLM route.
const kind = __ENV.BACKEND_TARGET || 'kb-detail';
const base = (__ENV.BACKEND_BASE_URL || 'http://127.0.0.1').replace(/\/$/, '');
if (!/^https?:\/\/[^\s?#]+$/.test(base)) throw new Error('Invalid BACKEND_BASE_URL');
function id(name) {
  const value = __ENV[name];
  if (!/^[1-9]\d*$/.test(value || '')) throw new Error('Missing/invalid fixture ID: ' + name);
  return value;
}
const workspace = id('BACKEND_WORKSPACE_ID');
const kb = id('BACKEND_KB_ID');
const root = `/api/workspaces/${workspace}/knowledge-bases/${kb}`;
let path;
if (kind === 'kb-detail') path = root;
else if (kind === 'document-list') path = root + '/documents?page=1&size=20';
else if (kind === 'document-status') path = root + '/documents/' + id('BACKEND_DOCUMENT_ID');
else throw new Error('Only kb-detail/document-list/document-status are supported');
if (!__ENV.BACKEND_ACCESS_TOKEN) throw new Error('BACKEND_ACCESS_TOKEN must be supplied privately');
const vus = Number(__ENV.BACKEND_VUS || 1);
const iterations = Number(__ENV.BACKEND_ITERATIONS || 10);
if (!Number.isInteger(vus) || vus < 1 || vus > 10 || !Number.isInteger(iterations) || iterations < 1 || iterations > 1000)
  throw new Error('Preparation limits: VUs 1..10; iterations 1..1000');

export const options = {
  scenarios: { backend_read: { executor: 'shared-iterations', vus, iterations, maxDuration: '2m' } },
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  thresholds: { http_req_failed: ['rate==0'], checks: ['rate==1'] },
};

export default function () {
  const response = http.get(base + path, {
    headers: { Authorization: 'Bearer ' + __ENV.BACKEND_ACCESS_TOKEN },
    tags: { name: 'backend-' + kind }, timeout: '10s', redirects: 0,
  });
  check(response, { 'authorized read is HTTP 200': r => r.status === 200 });
  sleep(0.1);
}
