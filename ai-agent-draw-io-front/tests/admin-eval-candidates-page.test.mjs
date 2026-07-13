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
  assert.match(page, /adminPrepareEvalDraft/);
  assert.match(page, /human review required/);
  assert.match(page, /adminStartSemanticMinerRun/);
  assert.match(page, /MODEL_DETECTED/);
  assert.match(page, /modelEvidence/);
  assert.match(page, /cannot approve, publish, or block a release/i);
  assert.match(page, /adminAnalyzeVisualRun/);
  assert.match(page, /adminAnalyzeVisualRun\(visualRunId\.trim\(\), true\)/);
  assert.match(page, /adminPrepareEvalDraft\(candidate\.id, true\)/);
  assert.match(page, /window\.confirm/);
  assert.match(page, /Pixels are inline, short-lived in memory, audited/);
  assert.doesNotMatch(page, /adminRunCaptures|Approve draft|Publish draft/i);
});
