import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const read = (path) => readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8');

const shell = read('../src/app/admin/admin-shell.tsx');
const navigation = await import('../src/app/admin/admin-navigation.mjs');
const evaluationWorkspace = read('../src/app/admin/evaluation-workspace.tsx');
const traceWorkspace = read('../src/app/admin/trace-analysis-workspace.tsx');
const evaluationOverview = read('../src/app/admin/evaluations/page.tsx');
const legacyCandidates = read('../src/app/admin/eval-candidates/page.tsx');
const canonicalFindings = read('../src/app/admin/trace-findings/page.tsx');
const traceRuns = read('../src/app/admin/runs/page.tsx');
const traceDetail = read('../src/app/admin/runs/[runId]/page.tsx');
const evalRunDetail = read('../src/app/admin/eval-runs/[evalRunId]/page.tsx');
const operations = read('../src/app/admin/eval-operations/page.tsx');
const cases = read('../src/app/admin/eval-cases/page.tsx');
const datasets = read('../src/app/admin/eval-datasets/page.tsx');
const evalRuns = read('../src/app/admin/eval-runs/page.tsx');

test('primary navigation separates Evaluation from Trace Analysis', () => {
  assert.deepEqual(
    navigation.primaryNavItems.map((item) => item.label),
    ['Overview', 'Evaluation', 'Trace Analysis', 'Operations'],
  );
  assert.equal(navigation.primaryNavItems.find((item) => item.id === 'evaluation').href, '/admin/evaluations');
  assert.equal(navigation.primaryNavItems.find((item) => item.id === 'traceAnalysis').href, '/admin/runs');
  assert.equal(navigation.primaryNavIdFor('evalRuns'), 'evaluation');
  assert.equal(navigation.primaryNavIdFor('cases'), 'evaluation');
  assert.equal(navigation.primaryNavIdFor('runs'), 'traceAnalysis');
  assert.equal(navigation.primaryNavIdFor('trace'), 'traceAnalysis');
  assert.equal(navigation.primaryNavIdFor('candidates'), 'traceAnalysis');
});

test('admin navigation remains usable on desktop and mobile widths', () => {
  assert.match(shell, /flex-wrap/);
  assert.match(shell, /order-3 grid h-10 w-full/);
  assert.match(shell, /sm:absolute sm:left-1\/2/);
  assert.match(shell, /gridTemplateColumns/);
});

test('Evaluation has an overview and contains no Trace Inbox navigation', () => {
  assert.match(evaluationOverview, /EvaluationWorkspace/);
  assert.match(evaluationOverview, /active="overview"/);
  assert.match(evaluationOverview, /No production traffic required/);
  assert.doesNotMatch(evaluationOverview, /redirect\(/);
  assert.match(evaluationWorkspace, /Overview/);
  assert.match(evaluationWorkspace, /Cases/);
  assert.match(evaluationWorkspace, /Datasets/);
  assert.match(evaluationWorkspace, /Runs/);
  assert.doesNotMatch(evaluationWorkspace, /Trace Inbox|eval-candidates/);
});

test('Trace Analysis owns runs, findings and miner entry points', () => {
  assert.match(traceWorkspace, /Trace Runs/);
  assert.match(traceWorkspace, /Findings/);
  assert.match(traceWorkspace, /\/admin\/trace-findings/);
  assert.match(traceWorkspace, /Findings are evidence, not Evaluation failures/);
  assert.match(traceRuns, /TraceAnalysisWorkspace/);
  assert.match(traceRuns, /active="runs"/);
  assert.match(traceDetail, /TraceAnalysisWorkspace active="runs"/);
  assert.match(legacyCandidates, /TraceAnalysisWorkspace active="findings"/);
  assert.match(legacyCandidates, /Choose rules, an LLM, or a VLM to screen a bounded Trace sample/);
  assert.match(legacyCandidates, /Visual anomaly discovery/);
});

test('the new Findings URL and legacy Candidate deep link use one implementation', () => {
  assert.match(canonicalFindings, /eval-candidates\/page/);
  assert.match(legacyCandidates, /export default function AdminEvalCandidatesPage/);
  assert.match(evalRunDetail, /href="\/admin\/trace-findings"/);
  assert.match(operations, /href="\/admin\/trace-findings"/);
});

test('both workspaces provide honest empty states', () => {
  assert.match(traceRuns, /No runs found/);
  assert.match(legacyCandidates, /No Findings need review/);
  assert.match(evaluationOverview, /Create Case/);
});

test('existing Evaluation pages remain connected to the Evaluation workspace', () => {
  assert.match(cases, /EvaluationWorkspace active="cases"/);
  assert.match(datasets, /EvaluationWorkspace active="datasets"/);
  assert.match(evalRuns, /EvaluationWorkspace active="runs"/);
});
