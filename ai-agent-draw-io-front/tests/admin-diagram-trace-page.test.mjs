import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const pagePath = fileURLToPath(new URL('../src/app/admin/runs/[runId]/page.tsx', import.meta.url));
const pageSource = readFileSync(pagePath, 'utf8');

test('admin run detail is framed as a Diagram Trace workspace', () => {
  // The current workspace keeps request, execution, inspection, and diagram outcome visible together.
  assert.match(pageSource, /Diagram trace/);
  assert.match(pageSource, /Trace progression/);
  assert.match(pageSource, /label="Request"/);
  assert.match(pageSource, /label="Agent route"/);
  assert.match(pageSource, /label="Diagram outcome"/);
  assert.match(pageSource, /Trace execution/);
  assert.match(pageSource, /Trace inspector/);
  assert.match(pageSource, /Payload evidence/);
});

test('admin run detail consumes the P1 unified diagram trace span model', () => {
  assert.match(pageSource, /AdminDiagramTraceDTO/);
  assert.match(pageSource, /adminDiagramTrace\(runId\)/);
  assert.match(pageSource, /trace\?\.spans/);
  assert.match(pageSource, /traceKind\(event\)/);
  assert.match(pageSource, /traceDisplayName\(selected\)/);
});

test('admin run detail shows P2 diagram effect evidence on selected spans', () => {
  assert.match(pageSource, /Diagram effect/);
  assert.match(pageSource, /Diagram ID/);
  assert.match(pageSource, /Before hash/);
  assert.match(pageSource, /After hash/);
  assert.match(pageSource, /Render status/);
  assert.match(pageSource, /XML changed/);
  assert.match(pageSource, /Thumbnail changed/);
});

test('admin run detail shows P3 runtime findings and can jump to related spans', () => {
  assert.match(pageSource, /Trace findings/);
  assert.match(pageSource, /trace\?\.findings/);
  assert.match(pageSource, /finding\.spanId/);
  assert.match(pageSource, /setSelectedId\(finding\.spanId\)/);
});

test('admin run detail shows P4 diagram snapshot filmstrip', () => {
  assert.match(pageSource, /Diagram evolution/);
  assert.match(pageSource, /trace\?\.snapshots/);
  assert.match(pageSource, /snapshot\.spanId/);
  assert.match(pageSource, /snapshot\.canvasHash/);
});

test('admin run detail supports P5 snapshot replay in the diagram preview', () => {
  assert.match(pageSource, /selectedSnapshotId/);
  assert.match(pageSource, /playingReplay/);
  assert.match(pageSource, /activeSnapshot/);
  assert.match(pageSource, /setSelectedSnapshotId\(snapshot\.id/);
  assert.match(pageSource, /setPlayingReplay/);
});

test('admin run detail starts a persistent deterministic, LLM, or VLM analysis job', () => {
  assert.match(pageSource, /Analyze trace/);
  assert.match(pageSource, /adminStartTraceAnalysis\(runId, traceAnalyzer\)/);
  assert.match(pageSource, /DETERMINISTIC/);
  assert.match(pageSource, /LLM semantic/);
  assert.match(pageSource, /VLM visual/);
  assert.match(pageSource, /persistent job owns retries and evidence isolation/);
  assert.match(pageSource, /adminGetTraceAnalysisJob/);
  assert.match(pageSource, /Recent Findings/);
});
