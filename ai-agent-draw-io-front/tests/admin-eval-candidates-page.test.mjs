import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const page = readFileSync(fileURLToPath(new URL('../src/app/admin/eval-candidates/page.tsx', import.meta.url)), 'utf8');

test('candidate queue exposes deterministic evidence, filters, trace links, and guarded transitions', () => {
  assert.match(page, /adminListEvalCandidates/);
  assert.match(page, /failureFamily/);
  assert.match(page, /evidenceSummary/);
  assert.match(page, /Open source trace/);
  assert.match(page, /TRIAGED/);
  assert.match(page, /UNDER_REVIEW/);
  assert.match(page, /REJECTED/);
  assert.doesNotMatch(page, /debug-trace|adminRunCaptures|LLM/i);
});
