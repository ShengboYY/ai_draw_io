import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const pagePath = fileURLToPath(new URL('../src/app/admin/runs/[runId]/page.tsx', import.meta.url));
const pageSource = readFileSync(pagePath, 'utf8');

test('admin run detail is framed as a Diagram Trace workspace', () => {
  // P0 should keep the same telemetry panels while changing the page information architecture.
  assert.match(pageSource, /Diagram Trace/);
  assert.match(pageSource, /Trace Summary/);
  assert.match(pageSource, /Request → Agent route → Diagram outcome/);
  assert.match(pageSource, /Diagram Trace Timeline/);
  assert.match(pageSource, /Diagram Outcome/);
  assert.match(pageSource, /Trace Inspector/);
  assert.match(pageSource, /Payload Evidence/);
});
