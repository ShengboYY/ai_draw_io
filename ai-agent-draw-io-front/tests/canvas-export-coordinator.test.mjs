import test from 'node:test';
import assert from 'node:assert/strict';

import {
  CanvasExportCoordinator,
  CanvasExportError,
} from '../src/app/drawio/canvas-export-coordinator.ts';

const request = (purpose, format = 'xmlsvg', scope = {}) => ({
  purpose,
  diagramId: scope.diagramId || 'diagram-1',
  sessionId: scope.sessionId || 'session-1',
  format,
  options: { format },
  timeoutMs: scope.timeoutMs || 100,
});

test('exports are dispatched FIFO with only one request in flight', async () => {
  const dispatched = [];
  const coordinator = new CanvasExportCoordinator(options => dispatched.push(options));

  const chat = coordinator.enqueue(request('chat-xml'));
  const thumbnail = coordinator.enqueue(request('thumbnail-png', 'png'));

  assert.deepEqual(dispatched, [{ format: 'xmlsvg' }]);
  assert.equal(coordinator.handleExport({ format: 'png', data: 'data:image/png;base64,old' }), false);
  assert.deepEqual(dispatched, [{ format: 'xmlsvg' }]);

  assert.equal(coordinator.handleExport({ format: 'xmlsvg', xml: '<mxGraphModel />' }), true);
  assert.deepEqual(dispatched, [{ format: 'xmlsvg' }, { format: 'png' }]);
  assert.equal(coordinator.handleExport({ format: 'png', data: 'data:image/png;base64,new' }), true);

  assert.equal((await chat).purpose, 'chat-xml');
  assert.equal((await thumbnail).purpose, 'thumbnail-png');
});

test('timeout rejects the stalled request and advances the queue', async () => {
  const dispatched = [];
  const coordinator = new CanvasExportCoordinator(options => dispatched.push(options));

  const stalled = coordinator.enqueue(request('autosave-xml', 'xmlsvg', { timeoutMs: 10 }));
  const next = coordinator.enqueue(request('visual-review-png', 'png'));

  await assert.rejects(stalled, error => (
    error instanceof CanvasExportError && error.code === 'EXPORT_TIMEOUT'
  ));
  assert.deepEqual(dispatched, [{ format: 'xmlsvg' }, { format: 'png' }]);

  coordinator.handleExport({ format: 'png', data: 'data:image/png;base64,next' });
  assert.equal((await next).purpose, 'visual-review-png');
});

test('scope changes reject queued work but keep an active stale export as a response barrier', async () => {
  const dispatched = [];
  const coordinator = new CanvasExportCoordinator(options => dispatched.push(options));

  const oldActive = coordinator.enqueue(request('chat-xml'));
  const oldQueued = coordinator.enqueue(request('thumbnail-png', 'png'));
  const newQueued = coordinator.enqueue(request('chat-xml', 'xmlsvg', {
    diagramId: 'diagram-2',
    sessionId: 'session-2',
  }));

  coordinator.retainScope({ diagramId: 'diagram-2', sessionId: 'session-2' });
  await assert.rejects(oldActive, error => error.code === 'EXPORT_SCOPE_CHANGED');
  await assert.rejects(oldQueued, error => error.code === 'EXPORT_SCOPE_CHANGED');
  assert.deepEqual(dispatched, [{ format: 'xmlsvg' }]);

  // Consume the old iframe response before dispatching the same-format request for the new canvas.
  assert.equal(coordinator.handleExport({ format: 'xmlsvg', xml: '<old />' }), true);
  assert.deepEqual(dispatched, [{ format: 'xmlsvg' }, { format: 'xmlsvg' }]);
  coordinator.handleExport({ format: 'xmlsvg', xml: '<new />' });
  assert.equal((await newQueued).diagramId, 'diagram-2');
});
