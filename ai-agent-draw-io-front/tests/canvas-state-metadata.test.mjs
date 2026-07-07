import test from 'node:test';
import assert from 'node:assert/strict';

import {
  makeLocalDiagramId,
  mergeCanvasStateMetadata,
} from '../src/app/drawio/canvas-state-metadata.ts';

test('makeLocalDiagramId creates a stable diagram id from the local session id', () => {
  assert.equal(makeLocalDiagramId('session-123'), 'diagram-session-123');
});

test('mergeCanvasStateMetadata applies server diagram version without dropping existing id', () => {
  const merged = mergeCanvasStateMetadata(
    { diagramId: 'diagram-1', version: 3, contentHash: 'old-hash' },
    { version: 4, contentHash: 'new-hash' }
  );

  assert.deepEqual(merged, { diagramId: 'diagram-1', version: 4, contentHash: 'new-hash' });
});

test('mergeCanvasStateMetadata ignores empty server metadata', () => {
  const merged = mergeCanvasStateMetadata(
    { diagramId: 'diagram-1', version: 3, contentHash: 'known-hash' },
    { diagramId: '', version: Number.NaN, contentHash: '' }
  );

  assert.deepEqual(merged, { diagramId: 'diagram-1', version: 3, contentHash: 'known-hash' });
});
