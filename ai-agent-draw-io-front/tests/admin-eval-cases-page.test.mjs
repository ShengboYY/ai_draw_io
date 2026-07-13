import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const read = (path) => readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8');

test('case studio exposes qualification lifecycle and only publishes an approved case', () => {
  const list = read('../src/app/admin/eval-cases/page.tsx');
  const studio = read('../src/app/admin/eval-cases/[workingCopyId]/page.tsx');
  assert.match(list, /adminListEvalCaseWorkingCopies/);
  assert.match(list, /New case/);
  assert.match(list, /visiblePublished/);
  assert.match(list, /No published Cases match this target/);
  assert.match(studio, /adminValidateEvalCase/);
  assert.match(studio, /adminDryRunEvalCase/);
  assert.match(studio, /g\.graderName/);
  assert.doesNotMatch(studio, /g\.grader:/);
  assert.match(studio, /adminSubmitEvalCaseReview/);
  assert.match(studio, /Approve/);
  assert.match(studio, /YAML preview/);
  assert.match(studio, /item\?\.status === 'APPROVED'/);
  assert.match(studio, /adminPublishEvalCase/);
  assert.match(studio, /adminGetEvalCaseSourceFinding/);
  assert.match(studio, /Source Finding \(restricted\)/);
  assert.match(studio, /Open audited source Trace/);
});

test('trace promotion uses the idempotent bridge instead of creating a raw linked working copy', () => {
  const candidates = read('../src/app/admin/eval-candidates/page.tsx');
  const api = read('../src/api/agent.ts');
  assert.match(api, /promote-to-eval-draft/);
  assert.match(api, /EvalCasePromotionResultDTO/);
  assert.match(candidates, /data\.workingCopyId/);
  assert.doesNotMatch(api, /sourceType: 'TRACE_DRAFT', candidateId/);
});
