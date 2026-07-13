import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const read = (path) => readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8');

test('case studio exposes qualification lifecycle without publishing early', () => {
  const list = read('../src/app/admin/eval-cases/page.tsx');
  const studio = read('../src/app/admin/eval-cases/[workingCopyId]/page.tsx');
  assert.match(list, /adminListEvalCaseWorkingCopies/);
  assert.match(list, /New case/);
  assert.match(studio, /adminValidateEvalCase/);
  assert.match(studio, /adminDryRunEvalCase/);
  assert.match(studio, /adminSubmitEvalCaseReview/);
  assert.match(studio, /Approve/);
  assert.match(studio, /YAML preview/);
  assert.doesNotMatch(studio, /adminPublishEvalCase/);
});
