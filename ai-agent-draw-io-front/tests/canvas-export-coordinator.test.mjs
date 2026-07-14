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

const formats = dispatched => dispatched.map(({ format }) => ({ format }));
const responseFor = (dispatched, index, payload) => ({
  ...payload,
  requestId: dispatched[index].requestId,
});

test('exports are dispatched FIFO with only one request in flight', async () => {
  const dispatched = [];
  const coordinator = new CanvasExportCoordinator(options => dispatched.push(options));

  const chat = coordinator.enqueue(request('chat-xml'));
  const thumbnail = coordinator.enqueue(request('thumbnail-png', 'png'));

  assert.deepEqual(formats(dispatched), [{ format: 'xmlsvg' }]);
  assert.equal(coordinator.handleExport(responseFor(dispatched, 0, {
    format: 'png', data: 'data:image/png;base64,old',
  })), false);
  assert.equal(coordinator.handleExport({ format: 'xmlsvg', xml: '<unscoped />' }), false);
  assert.deepEqual(formats(dispatched), [{ format: 'xmlsvg' }]);

  assert.equal(coordinator.handleExport(responseFor(dispatched, 0, {
    format: 'xmlsvg', xml: '<mxGraphModel />',
  })), true);
  assert.deepEqual(formats(dispatched), [{ format: 'xmlsvg' }, { format: 'png' }]);
  assert.equal(coordinator.handleExport(responseFor(dispatched, 1, {
    format: 'png', data: 'data:image/png;base64,new',
  })), true);

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
  assert.deepEqual(formats(dispatched), [{ format: 'xmlsvg' }, { format: 'png' }]);

  coordinator.handleExport(responseFor(dispatched, 1, {
    format: 'png', data: 'data:image/png;base64,next',
  }));
  assert.equal((await next).purpose, 'visual-review-png');
});

test('same-format timeout advances immediately and rejects the late correlated callback', async () => {
  const dispatched = [];
  const coordinator = new CanvasExportCoordinator(options => dispatched.push(options));

  const stalled = coordinator.enqueue(request('thumbnail-png', 'png', { timeoutMs: 10 }));
  const next = coordinator.enqueue(request('visual-review-png', 'png'));

  await assert.rejects(stalled, error => error.code === 'EXPORT_TIMEOUT');
  assert.deepEqual(formats(dispatched), [{ format: 'png' }, { format: 'png' }]);

  assert.equal(coordinator.handleExport(responseFor(dispatched, 0, {
    format: 'png', data: 'data:image/png;base64,late',
  })), false);
  coordinator.handleExport(responseFor(dispatched, 1, {
    format: 'png', data: 'data:image/png;base64,current',
  }));
  assert.equal((await next).data, 'data:image/png;base64,current');
});

test('scope changes reject stale work and correlate the next same-format response', async () => {
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
  assert.deepEqual(formats(dispatched), [{ format: 'xmlsvg' }, { format: 'xmlsvg' }]);

  assert.equal(coordinator.handleExport(responseFor(dispatched, 0, {
    format: 'xmlsvg', xml: '<old />',
  })), false);
  coordinator.handleExport(responseFor(dispatched, 1, { format: 'xmlsvg', xml: '<new />' }));
  assert.equal((await newQueued).diagramId, 'diagram-2');
});
