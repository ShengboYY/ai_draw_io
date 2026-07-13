import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const page = readFileSync(fileURLToPath(new URL('../src/app/admin/eval-operations/page.tsx', import.meta.url)), 'utf8');
const api = readFileSync(fileURLToPath(new URL('../src/api/agent.ts', import.meta.url)), 'utf8');

test('operations page visualizes all canary recommendations without automatic rollback', () => {
  assert.match(page, /CONTINUE/);
  assert.match(page, /HALT_RECOMMENDED/);
  assert.match(page, /NO_DECISION/);
  assert.match(page, /never deploy or roll back automatically/);
  assert.doesNotMatch(api, /adminRollbackEval|adminDeployEval/);
});

test('operations page exposes the non-sensitive Case Health queue and feedback loop', () => {
  assert.match(page, /adminListEvalCaseHealth/);
  assert.match(page, /adminRefreshEvalCaseHealth/);
  assert.match(page, /Case Health queue/);
  assert.match(page, /Regression feedback loop/);
  assert.match(page, /\/admin\/trace-findings/);
  assert.match(page, /\/admin\/eval-cases/);
  assert.match(page, /\/admin\/eval-runs/);
});

test('operations page exposes calibration, sequestered readiness and target Gate composition', () => {
  assert.match(page, /Calibration & sequestered readiness/);
  assert.match(page, /adminGetEvalRunInsights/);
  assert.match(page, /Compose target Gates/);
  assert.match(page, /Required for release/);
  assert.match(page, /PASS 0 · BLOCK 1 · NO_DECISION 2/);
  assert.match(api, /adminComposeEvalReleaseGate/);
  assert.match(api, /eval-release-gates\/compose/);
});
