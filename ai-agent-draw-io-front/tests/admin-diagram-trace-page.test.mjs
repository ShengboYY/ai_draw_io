import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Trace details are now an inline master-detail pane under /admin/runs.
const pagePath = fileURLToPath(new URL('../src/app/admin/runs/run-trace-detail.tsx', import.meta.url));
const pageSource = readFileSync(pagePath, 'utf8');

test('admin run detail is an inline Trace Analysis workspace', () => {
  assert.match(pageSource, /export function RunTraceDetail/);
  assert.match(pageSource, /execution tree \| span inspector/);
  assert.match(pageSource, /traceViewMode/);
  assert.match(pageSource, /TracePayloadPanel/);
});

test('admin run detail consumes the P1 unified diagram trace span model', () => {
  assert.match(pageSource, /AdminDiagramTraceDTO/);
  assert.match(pageSource, /adminDiagramTrace\(runId\)/);
  assert.match(pageSource, /trace\?\.spans/);
  assert.match(pageSource, /traceKind\(event\)/);
  assert.match(pageSource, /traceDisplayName\(selected\)/);
});

test('admin run detail shows P2 diagram effect evidence on selected spans', () => {
  assert.match(pageSource, /selected\.diagramEffect/);
  assert.match(pageSource, /Diagram after this span/);
});

test('admin run detail shows runtime findings and analysis controls', () => {
  assert.match(pageSource, /trace\?\.findings/);
  assert.match(pageSource, /analysisFindings/);
  assert.match(pageSource, /adminStartTraceAnalysis\(runId, traceAnalyzer\)/);
});

test('admin run detail shows the current diagram preview contract', () => {
  assert.match(pageSource, /aria-label="Diagram preview"/);
  assert.match(pageSource, /selected\.diagramEffect\.thumbnailUrl/);
});

test('admin run detail starts a persistent deterministic, LLM, or VLM analysis job', () => {
  assert.match(pageSource, /adminStartTraceAnalysis\(runId, traceAnalyzer\)/);
  assert.match(pageSource, /DETERMINISTIC/);
  assert.match(pageSource, /LLM/);
  assert.match(pageSource, /VLM/);
  assert.match(pageSource, /persistent job owns retries and evidence isolation/);
  assert.match(pageSource, /adminGetTraceAnalysisJob/);
  assert.match(pageSource, /analysisFindings/);
});
