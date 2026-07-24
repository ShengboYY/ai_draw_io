import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildFilesPanelGroups,
  filesPanelStatusLabel,
} from '../src/features/files/files-panel-model.ts';

const file = (materialId, displayName, processingStatus = 'READY', searchStatus = 'NOT_APPLICABLE') => ({
  materialId,
  kind: 'PDF',
  displayName,
  retentionClass: 'TEMPORARY',
  lifecycleState: 'ACTIVE',
  latestVersionId: `${materialId}-version`,
  processingStatus,
  searchStatus,
  progress: 100,
  updatedAt: '2026-07-24T00:00:00Z',
});

test('files panel hides the shared group when the diagram has no chartbook', () => {
  const groups = buildFilesPanelGroups({
    hasChartbook: false,
    conversationFiles: [file('conversation-1', 'notes.pdf')],
    chartbookFiles: [file('shared-1', 'requirements.pdf')],
  });

  assert.equal(groups.chartbookSharedFiles, undefined);
  assert.deepEqual(groups.conversationFiles.map(item => item.materialId), ['conversation-1']);
});

test('chartbook scope wins when the same file also has conversation scope', () => {
  const shared = file('material-1', 'architecture.png', 'READY', 'SEARCHABLE');
  const groups = buildFilesPanelGroups({
    hasChartbook: true,
    conversationFiles: [file('material-1', 'architecture.png'), file('material-2', 'notes.pdf')],
    chartbookFiles: [shared],
  });

  assert.deepEqual(groups.chartbookSharedFiles?.map(item => item.materialId), ['material-1']);
  assert.deepEqual(groups.conversationFiles.map(item => item.materialId), ['material-2']);
});

test('files panel exposes only product status labels', () => {
  assert.equal(filesPanelStatusLabel('UPLOADING_BYTES'), 'Uploading');
  assert.equal(filesPanelStatusLabel('COMPLETING'), 'Checking');
  assert.equal(filesPanelStatusLabel('PROCESSING'), 'Processing');
  assert.equal(filesPanelStatusLabel('EXTRACTING'), 'Processing');
  assert.equal(filesPanelStatusLabel('OCR_VISUAL'), 'Processing');
  assert.equal(filesPanelStatusLabel('PARTIAL_READY'), 'Search limited');
  assert.equal(filesPanelStatusLabel('READY'), 'Ready');
  assert.equal(filesPanelStatusLabel('INDEXING'), 'Indexing');
  assert.equal(filesPanelStatusLabel('SEARCHABLE'), 'Searchable');
  assert.equal(filesPanelStatusLabel('INDEX_FAILED'), 'Failed');
  assert.equal(filesPanelStatusLabel('REJECTED_SECURITY'), 'Rejected');
  assert.equal(filesPanelStatusLabel('VISUAL_PROVIDER_OUTPUT_INVALID'), 'Failed');
});
