import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const read = (path) => readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8');

const shell = read('../src/app/admin/admin-shell.tsx');
const navigation = await import('../src/app/admin/admin-navigation.mjs');
const workspace = read('../src/app/admin/evaluation-workspace.tsx');
const overview = read('../src/app/admin/evaluations/page.tsx');
const candidates = read('../src/app/admin/eval-candidates/page.tsx');
const cases = read('../src/app/admin/eval-cases/page.tsx');
const datasets = read('../src/app/admin/eval-datasets/page.tsx');
const runs = read('../src/app/admin/eval-runs/page.tsx');
const runDetail = read('../src/app/admin/eval-runs/[evalRunId]/page.tsx');

test('admin navigation separates telemetry from the evaluation workspace', () => {
  assert.deepEqual(navigation.primaryNavItems.map((item) => item.label), ['Overview', 'Traces', 'Evaluation', 'Operations']);
  assert.match(shell, /flex-wrap/);
  assert.match(shell, /order-3 grid h-10 w-full/);
  assert.equal(navigation.primaryNavItems.some((item) => item.label === 'Candidates'), false);
  assert.equal(navigation.primaryNavItems.some((item) => item.label === 'Cases'), false);
  assert.equal(navigation.primaryNavItems.some((item) => item.label === 'Datasets'), false);
  assert.equal(navigation.primaryNavIdFor('trace'), 'traces');
  assert.equal(navigation.primaryNavIdFor('candidates'), 'evaluation');
  assert.equal(navigation.primaryNavIdFor('datasets'), 'evaluation');
});

test('evaluation workspace presents one complete and repeatable workflow', () => {
  assert.match(workspace, /Discover/);
  assert.match(workspace, /Build Cases/);
  assert.match(workspace, /Curate Dataset/);
  assert.match(workspace, /Run & Inspect/);
  assert.match(workspace, /improve &amp; repeat/i);
  assert.match(workspace, /aria-current/);
  assert.ok(workspace.indexOf('Build Cases') < workspace.indexOf('Discover & Repeat'));
});

test('evaluation landing page prioritizes offline evaluation and trace feedback', () => {
  assert.match(overview, /Start with offline evaluation/);
  assert.match(overview, /No production traffic required/);
  assert.match(overview, /Turn development traces into regression Cases/);
  assert.match(overview, /Recorded-model replay/);
  assert.ok(overview.includes('/admin/eval-cases/new'));
  assert.ok(overview.includes('/admin/eval-candidates'));
});

test('every core evaluation page is connected to the shared workflow', () => {
  assert.match(candidates, /EvaluationWorkspace active="discover"/);
  assert.match(cases, /EvaluationWorkspace active="cases"/);
  assert.match(datasets, /EvaluationWorkspace active="datasets"/);
  assert.match(runs, /EvaluationWorkspace active="runs"/);
  assert.match(runs, /Recorded-model replay/);
  assert.match(datasets, /adminListPublishedEvalCases/);
  assert.match(datasets, /Published Case to add/);
  assert.match(runs, /adminListEvalDatasets/);
  assert.match(runs, /adminListEvalDatasetVersions/);
  assert.match(runDetail, /Close the feedback loop/);
  assert.match(runDetail, /Trace Inbox/);
});
