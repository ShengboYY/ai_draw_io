import test from 'node:test';
import assert from 'node:assert/strict';

import {
  beginAiCanvasMutation,
  createCanvasPersistenceState,
  finishAiCanvasMutation,
  shouldRetryManualCanvasSaveConflict,
} from '../src/app/drawio/canvas-persistence-coordinator.ts';

test('beginAiCanvasMutation marks diagram in flight and drops matching pending save', () => {
  const state = createCanvasPersistenceState({
    pendingManualCanvasSave: { diagramId: 'diagram-1' },
  });

  const result = beginAiCanvasMutation(state, 'diagram-1');

  assert.equal(result.droppedPendingManualCanvasSave, true);
  assert.equal(state.pendingManualCanvasSave, null);
  assert.equal(state.aiCanvasMutationDiagramIds.has('diagram-1'), true);
});

test('beginAiCanvasMutation preserves unrelated pending save', () => {
  const state = createCanvasPersistenceState({
    pendingManualCanvasSave: { diagramId: 'diagram-2' },
  });

  const result = beginAiCanvasMutation(state, 'diagram-1');

  assert.equal(result.droppedPendingManualCanvasSave, false);
  assert.deepEqual(state.pendingManualCanvasSave, { diagramId: 'diagram-2' });
  assert.equal(state.aiCanvasMutationDiagramIds.has('diagram-1'), true);
});

test('finishAiCanvasMutation releases the diagram owner', () => {
  const state = createCanvasPersistenceState();
  beginAiCanvasMutation(state, 'diagram-1');

  finishAiCanvasMutation(state, 'diagram-1');

  assert.equal(state.aiCanvasMutationDiagramIds.has('diagram-1'), false);
});

test('shouldRetryManualCanvasSaveConflict does not retry while AI owns the diagram', () => {
  assert.equal(shouldRetryManualCanvasSaveConflict({
    queuedCanvasXml: '<mxGraphModel/>',
    currentCanvasXml: '<mxGraphModel/>',
    aiMutationInFlight: true,
  }), false);
});
