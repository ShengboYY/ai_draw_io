import test from 'node:test';
import assert from 'node:assert/strict';

import { buildCanvasStateConflictMessage } from '../src/app/drawio/canvas-state-conflict.ts';

test('buildCanvasStateConflictMessage uses a refreshable default message', () => {
  const message = buildCanvasStateConflictMessage({});

  assert.match(message, /diagram changed/i);
  assert.match(message, /refresh/i);
});

test('buildCanvasStateConflictMessage keeps backend detail and version hint', () => {
  const message = buildCanvasStateConflictMessage({
    content: 'Canvas state version conflict. Refresh the diagram and retry.',
    expectedVersion: 7,
  });

  assert.equal(
    message,
    'Canvas state version conflict. Refresh the diagram and retry. Expected version: 7.'
  );
});
