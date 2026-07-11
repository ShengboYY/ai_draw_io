import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const pagePath = fileURLToPath(new URL('../src/app/admin/runs/[runId]/page.tsx', import.meta.url));
const pageSource = readFileSync(pagePath, 'utf8');

test('admin run detail is framed as a Diagram Trace workspace', () => {
  // The page keeps the telemetry panels while framing them as diagram-specific trace work.
  assert.match(pageSource, /Diagram Trace/);
  assert.match(pageSource, /Trace Summary/);
  assert.match(pageSource, /Request → Agent route → Diagram outcome/);
  assert.match(pageSource, /Diagram Trace Timeline/);
  assert.match(pageSource, /Diagram Outcome/);
  assert.match(pageSource, /Trace Inspector/);
  assert.match(pageSource, /Payload Evidence/);
});

test('admin run detail consumes the P1 unified diagram trace span model', () => {
  assert.match(pageSource, /AdminDiagramTraceDTO/);
  assert.match(pageSource, /adminDiagramTrace\(runId\)/);
  assert.match(pageSource, /trace\?\.spans/);
  assert.match(pageSource, /traceKind\(event\)/);
  assert.match(pageSource, /traceDisplayName\(selected\)/);
});

test('admin run detail shows P2 diagram effect evidence on selected spans', () => {
  assert.match(pageSource, /Diagram Effect/);
  assert.match(pageSource, /Diagram ID/);
  assert.match(pageSource, /Canvas hash/);
  assert.match(pageSource, /Render status/);
  assert.match(pageSource, /XML changed/);
  assert.match(pageSource, /Thumbnail changed/);
});

test('admin run detail shows P3 runtime findings and can jump to related spans', () => {
  assert.match(pageSource, /Trace Findings/);
  assert.match(pageSource, /trace\?\.findings/);
  assert.match(pageSource, /finding\.spanId/);
  assert.match(pageSource, /setSelectedId\(finding\.spanId\)/);
});

test('admin run detail shows P4 diagram snapshot filmstrip', () => {
  assert.match(pageSource, /Evolution Filmstrip/);
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

test('admin run detail provides the metadata-only Trace-to-Eval manual entry', () => {
  assert.match(pageSource, /Create Eval Candidate/);
  assert.match(pageSource, /adminCreateEvalCandidate\(runId\)/);
  assert.match(pageSource, /evalCandidate\.status/);
  assert.match(pageSource, /P0 deliberately sends only the run id/);
});
