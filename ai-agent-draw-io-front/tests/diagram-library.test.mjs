import test from 'node:test';
import assert from 'node:assert/strict';

import {
  applyDiagramLibraryView,
  CATEGORY_LABELS,
  categoryLabel,
  countByFilter,
  isIllustration,
} from '../src/app/diagram-library.ts';

const diagram = (over) => ({
  diagramId: over.diagramId ?? Math.random().toString(36).slice(2),
  title: over.title,
  diagramType: over.diagramType,
  updatedAt: over.updatedAt,
});

const sample = [
  diagram({ diagramId: 'a', title: 'JVM architecture', diagramType: 'architecture', updatedAt: '2026-07-03T10:00:00Z' }),
  diagram({ diagramId: 'b', title: 'Cute dog', diagramType: 'illustration', updatedAt: '2026-07-04T10:00:00Z' }),
  diagram({ diagramId: 'c', title: 'Order flow', diagramType: 'flowchart', updatedAt: '2026-07-02T10:00:00Z' }),
  diagram({ diagramId: 'd', title: 'Untitled Diagram', diagramType: '', updatedAt: '2026-07-01T10:00:00Z' }),
];

test('categoryLabel falls back to "blank" for empty or none types', () => {
  assert.equal(categoryLabel(diagram({ diagramType: '' })), 'blank');
  assert.equal(categoryLabel(diagram({ diagramType: 'none' })), 'blank');
  assert.equal(categoryLabel(diagram({ diagramType: 'Architecture' })), 'architecture');
});

test('categoryLabel infers old basic diagrams from the title', () => {
  assert.equal(categoryLabel(diagram({ title: 'jvm架构图', diagramType: 'basic' })), 'architecture');
  assert.equal(categoryLabel(diagram({ title: '猫狗头像', diagramType: 'basic' })), 'illustration');
});

test('categoryLabel infers unknown diagram types from the title', () => {
  assert.equal(categoryLabel(diagram({ title: '订单审批流程', diagramType: 'custom' })), 'flowchart');
  assert.equal(categoryLabel(diagram({ title: '未命名灵感', diagramType: 'custom' })), 'others');
  assert.equal(categoryLabel(diagram({ title: 'legacy generic', diagramType: 'diagram' })), 'others');
});

test('categoryLabel keeps known diagram types instead of title inference', () => {
  assert.equal(categoryLabel(diagram({ title: '猫狗头像', diagramType: 'architecture' })), 'architecture');
});

test('CATEGORY_LABELS exposes English display names for known category tokens', () => {
  assert.equal(CATEGORY_LABELS.architecture, 'architecture');
  assert.equal(CATEGORY_LABELS.illustration, 'illustration');
  assert.equal(CATEGORY_LABELS.others, 'others');
});

test('isIllustration only matches the illustration category', () => {
  assert.equal(isIllustration(diagram({ diagramType: 'illustration' })), true);
  assert.equal(isIllustration(diagram({ title: '猫狗头像', diagramType: 'basic' })), true);
  assert.equal(isIllustration(diagram({ diagramType: 'flowchart' })), false);
  assert.equal(isIllustration(diagram({ diagramType: '' })), false);
});

test('illustrations filter keeps only illustration diagrams; diagrams filter excludes them', () => {
  const illustrations = applyDiagramLibraryView(sample, { query: '', filter: 'illustrations', sort: 'recent' });
  assert.deepEqual(illustrations.map(d => d.diagramId), ['b']);

  const diagrams = applyDiagramLibraryView(sample, { query: '', filter: 'diagrams', sort: 'recent' });
  assert.deepEqual(diagrams.map(d => d.diagramId).sort(), ['a', 'c', 'd']);
});

test('search matches title and category, case-insensitively', () => {
  assert.deepEqual(
    applyDiagramLibraryView(sample, { query: 'ARCH', filter: 'all', sort: 'recent' }).map(d => d.diagramId),
    ['a'],
  );
  // Matches by category label even when the title has no such word.
  assert.deepEqual(
    applyDiagramLibraryView(sample, { query: 'flowchart', filter: 'all', sort: 'recent' }).map(d => d.diagramId),
    ['c'],
  );
});

test('recent sort is newest-first and oldest sort is the reverse', () => {
  assert.deepEqual(
    applyDiagramLibraryView(sample, { query: '', filter: 'all', sort: 'recent' }).map(d => d.diagramId),
    ['b', 'a', 'c', 'd'],
  );
  assert.deepEqual(
    applyDiagramLibraryView(sample, { query: '', filter: 'all', sort: 'oldest' }).map(d => d.diagramId),
    ['d', 'c', 'a', 'b'],
  );
});

test('name sort is alphabetical by title', () => {
  assert.deepEqual(
    applyDiagramLibraryView(sample, { query: '', filter: 'all', sort: 'name' }).map(d => d.title),
    ['Cute dog', 'JVM architecture', 'Order flow', 'Untitled Diagram'],
  );
});

test('type sort groups by category then newest-first within a group', () => {
  const withDuplicateType = [
    ...sample,
    diagram({ diagramId: 'e', title: 'Newer architecture', diagramType: 'architecture', updatedAt: '2026-07-05T10:00:00Z' }),
  ];
  const ordered = applyDiagramLibraryView(withDuplicateType, { query: '', filter: 'all', sort: 'type' });
  // architecture group first (e before a because e is newer), then blank, flowchart, illustration.
  assert.deepEqual(ordered.map(d => d.diagramId), ['e', 'a', 'd', 'c', 'b']);
});

test('countByFilter ignores the active search text', () => {
  assert.equal(countByFilter(sample, 'all'), 4);
  assert.equal(countByFilter(sample, 'illustrations'), 1);
  assert.equal(countByFilter(sample, 'diagrams'), 3);
});
