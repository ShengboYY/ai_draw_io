import test from 'node:test';
import assert from 'node:assert/strict';

import {
  applyChartbookShelfView,
  chartbookFolderSummary,
} from '../src/features/chartbooks/chartbook-shelf.ts';
import {
  CHARTBOOKS_TAB,
  diagramFilterForTab,
  WORKSPACE_TABS,
} from '../src/app/diagram-library.ts';

const chartbook = (over) => ({
  chartbookId: over.chartbookId,
  name: over.name ?? 'Untitled',
  status: 'ACTIVE',
  diagramIds: over.diagramIds ?? [],
  materialIds: over.materialIds ?? [],
  createdAt: over.updatedAt ?? '',
  updatedAt: over.updatedAt ?? '',
});

const sample = [
  chartbook({ chartbookId: 'a', name: 'JVM notes', diagramIds: ['d1'], updatedAt: '2026-07-03T10:00:00Z' }),
  chartbook({ chartbookId: 'b', name: 'Payment flows', diagramIds: ['d2', 'd3'], materialIds: ['m1'], updatedAt: '2026-07-05T10:00:00Z' }),
  chartbook({ chartbookId: 'c', name: 'Sketches', updatedAt: '2026-07-01T10:00:00Z' }),
];

test('folder summary pluralizes diagram and shared item counts', () => {
  assert.equal(chartbookFolderSummary(sample[0]), '1 diagram · 0 shared items');
  assert.equal(chartbookFolderSummary(sample[1]), '2 diagrams · 1 shared item');
});

test('shelf view is newest-first and leaves the caller list untouched', () => {
  const ordered = applyChartbookShelfView(sample);
  assert.deepEqual(ordered.map(item => item.chartbookId), ['b', 'a', 'c']);
  assert.deepEqual(sample.map(item => item.chartbookId), ['a', 'b', 'c']);
});

test('shelf view searches folder names case-insensitively', () => {
  assert.deepEqual(
    applyChartbookShelfView(sample, 'PAY').map(item => item.chartbookId),
    ['b'],
  );
  assert.deepEqual(applyChartbookShelfView(sample, 'missing'), []);
  // Blank queries keep the whole shelf.
  assert.equal(applyChartbookShelfView(sample, '   ').length, 3);
});

test('chartbooks join the workspace tab strip without changing diagram filtering', () => {
  assert.deepEqual(WORKSPACE_TABS.map(tab => tab.id), ['all', 'diagrams', 'illustrations', CHARTBOOKS_TAB]);
  assert.equal(diagramFilterForTab(CHARTBOOKS_TAB), 'all');
  assert.equal(diagramFilterForTab('illustrations'), 'illustrations');
});
