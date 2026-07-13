import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const list = readFileSync(fileURLToPath(new URL('../src/app/admin/eval-runs/page.tsx', import.meta.url)), 'utf8');
const detail = readFileSync(fileURLToPath(new URL('../src/app/admin/eval-runs/[evalRunId]/page.tsx', import.meta.url)), 'utf8');
const targetReport = readFileSync(fileURLToPath(new URL('../src/app/admin/eval-runs/[evalRunId]/target-report-panel.tsx', import.meta.url)), 'utf8');

test('Eval Run UI exposes progress and keeps FAIL distinct from infrastructure ERROR', () => {
  assert.match(list, /progress/); assert.match(list, /PASS/); assert.match(list, /FAIL/); assert.match(list, /ERROR/);
  assert.match(detail, /Case Matrix|Grader matrix/); assert.match(detail, /Infrastructure error/);
  assert.match(detail, /Agent output was produced/); assert.match(detail, /adminGetEvalEpisodeArtifact/);
  assert.match(detail, /Blocking reason/);
  assert.match(detail, /Trace waterfall/); assert.match(detail, /Semantic diff/); assert.match(detail, /Canvas before/);
  assert.match(detail, /label="Agent"/);
  assert.doesNotMatch(list, /traceRef|artifactRef|initialCanvasXml|finalCanvasXml/);
  // Cost limits now come from the frozen Profile; the wizard must expose the Profile and candidate build instead.
  assert.match(list, /MODE_C/); assert.match(list, /RELEASE/); assert.match(list, /Evaluation Profile/); assert.match(list, /Candidate Git SHA/);
  assert.match(detail, /TSR@1/); assert.match(detail, /Paired delta/); assert.match(detail, /Text Judge/); assert.match(detail, /Visual Judge/);
  assert.match(detail, /Canvas before \/ after pixels/);
  assert.match(detail, /Release Gate/); assert.match(detail, /Release Owner override/);
  assert.match(list, /visibleRuns/); assert.match(list, /No Eval Runs match this target/);
  assert.match(detail, /parseStoredJson/); assert.match(detail, /Stored Judge evidence is not valid JSON/);
});

test('Eval Run UI renders target-specific reports and links metric evidence to Episodes', () => {
  assert.match(detail, /adminGetEvalTargetReport/);
  assert.match(detail, /TargetReportPanel/);
  assert.match(detail, /setInterval/);
  assert.match(targetReport, /Partial report/);
  assert.match(targetReport, /Confusion matrix/);
  assert.match(targetReport, /Failure funnel/);
  assert.match(targetReport, /Before \/ after evidence/);
  assert.match(targetReport, /p95 requires|p95:/);
  assert.match(targetReport, /onOpenEpisode/);
  assert.match(targetReport, /onClick=\{\(\) => onOpen/);
});
