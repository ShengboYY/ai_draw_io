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
