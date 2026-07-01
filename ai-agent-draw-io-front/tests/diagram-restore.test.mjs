import test from 'node:test';
import assert from 'node:assert/strict';

import { buildRestoredDiagramState } from '../src/app/drawio/diagram-restore.ts';

test('buildRestoredDiagramState maps backend diagram detail to local session state', () => {
  const restored = buildRestoredDiagramState({
    diagramId: 'diagram-1',
    title: 'Checkout Flow',
    currentXml: '<mxGraphModel/>',
    version: 4,
  });

  assert.deepEqual(restored, {
    diagramId: 'diagram-1',
    title: 'Checkout Flow',
    drawIoXml: '<mxGraphModel/>',
    canvasVersion: 4,
  });
});

test('buildRestoredDiagramState falls back to a readable title', () => {
  const restored = buildRestoredDiagramState({
    diagramId: 'diagram-1',
    currentXml: '<mxGraphModel/>',
  });

  assert.equal(restored.title, 'Restored Diagram');
});
