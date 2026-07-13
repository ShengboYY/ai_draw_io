import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const list = readFileSync(fileURLToPath(new URL('../src/app/admin/eval-runs/page.tsx', import.meta.url)), 'utf8');
const detail = readFileSync(fileURLToPath(new URL('../src/app/admin/eval-runs/[evalRunId]/page.tsx', import.meta.url)), 'utf8');

test('Eval Run UI exposes progress and keeps FAIL distinct from infrastructure ERROR', () => {
  assert.match(list, /progress/); assert.match(list, /PASS/); assert.match(list, /FAIL/); assert.match(list, /ERROR/);
  assert.match(detail, /Case Matrix|Grader matrix/); assert.match(detail, /Infrastructure error/);
  assert.match(detail, /Agent output was produced/); assert.match(detail, /adminGetEvalEpisodeArtifact/);
  assert.match(detail, /Blocking reason/);
  assert.match(detail, /Trace waterfall/); assert.match(detail, /Semantic diff/); assert.match(detail, /Canvas before/);
  assert.match(detail, /label="Agent"/);
  assert.doesNotMatch(list, /traceRef|artifactRef|initialCanvasXml|finalCanvasXml/);
  assert.match(list, /MODE_C/); assert.match(list, /RELEASE/); assert.match(list, /Budget \(USD\)/);
  assert.match(detail, /TSR@1/); assert.match(detail, /Paired delta/); assert.match(detail, /Judge calibration/);
  assert.match(detail, /Release Gate/); assert.match(detail, /Release Owner override/);
});
