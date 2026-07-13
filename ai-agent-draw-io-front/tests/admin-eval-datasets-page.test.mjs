import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const page = readFileSync(fileURLToPath(new URL('../src/app/admin/eval-datasets/page.tsx', import.meta.url)), 'utf8');

test('dataset studio pins case versions and exposes validation, publication, and raw coverage', () => {
  assert.match(page, /case-id@version/);
  assert.match(page, /adminReplaceEvalDatasetMembers/);
  assert.match(page, /adminEvalDatasetAction/);
  assert.match(page, /adminEvalDatasetCoverage/);
  assert.match(page, /Languages/);
  assert.match(page, /Diagram types/);
  assert.doesNotMatch(page, /Macro-F1|accuracy/i);
});
