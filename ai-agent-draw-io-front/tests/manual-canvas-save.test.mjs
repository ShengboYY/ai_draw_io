import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildManualCanvasSaveRequest,
  latestCanvasVersion,
} from '../src/app/drawio/manual-canvas-save.ts';

test('buildManualCanvasSaveRequest keeps the current canvas version for autosave', () => {
  const request = buildManualCanvasSaveRequest({
    userId: 'anon_123',
    sessionId: 'session-1',
    diagramId: 'diagram-1',
    canvasVersion: 4,
    canvasXml: '<mxGraphModel />',
  });

  assert.deepEqual(request, {
    userId: 'anon_123',
    sessionId: 'session-1',
    diagramId: 'diagram-1',
    expectedVersion: 4,
    canvasXml: '<mxGraphModel />',
  });
});

test('buildManualCanvasSaveRequest skips incomplete autosave payloads', () => {
  assert.equal(buildManualCanvasSaveRequest({
    userId: '',
    sessionId: 'session-1',
    diagramId: 'diagram-1',
    canvasXml: '<mxGraphModel />',
  }), null);
  assert.equal(buildManualCanvasSaveRequest({
    userId: 'anon_123',
    sessionId: 'session-1',
    diagramId: '',
    canvasXml: '<mxGraphModel />',
  }), null);
  assert.equal(buildManualCanvasSaveRequest({
    userId: 'anon_123',
    sessionId: 'session-1',
    diagramId: 'diagram-1',
    canvasXml: '   ',
  }), null);
});

test('latestCanvasVersion picks the highest known version', () => {
  assert.equal(latestCanvasVersion(4, 5), 5);
  assert.equal(latestCanvasVersion(5, 4), 5);
  assert.equal(latestCanvasVersion(undefined, 4), 4);
  assert.equal(latestCanvasVersion(4, undefined), 4);
});

test('latestCanvasVersion returns undefined when no version is known', () => {
  assert.equal(latestCanvasVersion(), undefined);
  assert.equal(latestCanvasVersion(undefined, undefined), undefined);
  assert.equal(latestCanvasVersion(Number.NaN), undefined);
});
