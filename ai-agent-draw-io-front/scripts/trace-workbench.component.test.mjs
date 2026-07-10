import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import test from 'node:test';

const load = createRequire(import.meta.url);
const React = load('react');
const { renderToStaticMarkup } = load('react-dom/server');
const ts = load('typescript');

// Transpile repository TSX in-memory so this test renders the production components without a test-only copy.
load.extensions['.tsx'] = (module, filename) => {
  const source = fs.readFileSync(filename, 'utf8');
  const result = ts.transpileModule(source, {
    fileName: filename,
    compilerOptions: {
      esModuleInterop: true,
      jsx: ts.JsxEmit.ReactJSX,
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
    },
  });
  module._compile(result.outputText, filename);
};

const { TracePayloadPanel } = load('../src/app/admin/runs/[runId]/trace-payload-panel.tsx');
const { TraceWorkbench } = load('../src/app/admin/runs/[runId]/trace-workbench.tsx');

test('renders the real three-column trace workbench in tree-inspector-preview order', () => {
  const html = renderToStaticMarkup(React.createElement(
    TraceWorkbench,
    { showDiagramPreview: true },
    React.createElement('section', { 'data-pane': 'tree' }, 'Tree'),
    React.createElement('section', { 'data-pane': 'inspector' }, 'Inspector'),
    React.createElement('aside', { 'data-pane': 'preview' }, 'Preview'),
  ));

  assert.match(html, /data-testid="trace-workbench"/);
  assert.match(html, /xl:grid-cols-\[minmax\(260px,0\.8fr\)_minmax\(420px,1\.25fr\)_minmax\(320px,0\.9fr\)\]/);
  assert.ok(html.indexOf('data-pane="tree"') < html.indexOf('data-pane="inspector"'));
  assert.ok(html.indexOf('data-pane="inspector"') < html.indexOf('data-pane="preview"'));
});

test('renders real span input/output controls and retention states', () => {
  const payloads = [
    {
      id: 'input',
      payloadKind: 'INPUT',
      contentType: 'application/json',
      content: '{"contents":[{"role":"user","parts":[{"text":"draw it"}]}]}',
      originalLength: 62,
    },
    {
      id: 'output',
      payloadKind: 'OUTPUT',
      contentType: 'application/json',
      content: '{"finishReason":"STOP","content":{"role":"model","parts":[{"text":"done"}]}}',
      originalLength: 120000,
      truncated: true,
    },
    {
      id: 'expired',
      payloadKind: 'TOOL_RESULT',
      contentExpiresAt: '2000-01-01T00:00:00Z',
    },
    {
      id: 'redacted',
      payloadKind: 'ERROR',
      contentExpiresAt: '2999-01-01T00:00:00Z',
    },
  ];
  const html = renderToStaticMarkup(React.createElement(TracePayloadPanel, {
    payloads,
    loading: false,
  }));

  assert.ok(html.indexOf('>Input<') < html.indexOf('>Output<'));
  for (const label of ['rendered', 'raw', 'Search INPUT', 'Copy', 'Fullscreen', 'Collapse']) {
    assert.ok(html.includes(label), `missing rendered control: ${label}`);
  }
  for (const status of ['Captured', 'Truncated', 'Expired', 'Redacted']) {
    assert.ok(html.includes(status), `missing payload status: ${status}`);
  }
  assert.match(html, /finish:\s*STOP/);
  assert.match(html, /120,000 chars/);
});
