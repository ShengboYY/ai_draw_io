import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildDiagramHistoryEntries,
  diagramHistoryTitle,
} from '../src/app/drawio/diagram-history.ts';

test('buildDiagramHistoryEntries sorts database diagrams by updated time', () => {
  const entries = buildDiagramHistoryEntries([
    { diagramId: 'older', title: 'Older', updatedAt: '2026-07-03T10:00:00.000Z' },
    { diagramId: 'newer', title: 'Newer', updatedAt: '2026-07-04T10:00:00.000Z' },
  ]);

  assert.deepEqual(entries.map(entry => entry.diagramId), ['newer', 'older']);
});

test('buildDiagramHistoryEntries skips entries without database ids', () => {
  const entries = buildDiagramHistoryEntries([
    { diagramId: '', title: 'Local draft', updatedAt: '2026-07-04T10:00:00.000Z' },
    { diagramId: 'diagram-1', title: 'Saved', updatedAt: '2026-07-04T09:00:00.000Z' },
  ]);

  assert.deepEqual(entries.map(entry => entry.diagramId), ['diagram-1']);
});

test('diagramHistoryTitle falls back for untitled database diagrams', () => {
  assert.equal(diagramHistoryTitle({ diagramId: 'diagram-1', title: '  ' }), 'Untitled Diagram');
});
