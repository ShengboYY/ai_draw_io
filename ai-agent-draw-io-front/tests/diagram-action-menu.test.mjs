import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const source = relativePath => readFileSync(
  fileURLToPath(new URL(`../src/${relativePath}`, import.meta.url)),
  'utf8',
);

test('diagram cards use one chartbook-style action menu', () => {
  const menuSource = source('features/diagrams/DiagramActionMenu.tsx');
  const diagramsPage = source('app/diagrams/page.tsx');
  const chartbookPage = source('app/chartbooks/details/page.tsx');

  // These are the distinctive dimensions and treatments of the chartbook menu reference.
  assert.match(menuSource, /w-44 rounded-xl border border-stone-200\/90 bg-white p-1\.5/);
  assert.match(menuSource, /h-8 w-8 shrink-0 items-center justify-center rounded-lg/);
  assert.match(menuSource, /shadow-\[0_14px_35px_rgba\(24,24,27,0\.14\)\]/);
  assert.match(diagramsPage, /<DiagramActionMenu/);
  assert.match(chartbookPage, /<DiagramActionMenu/);
});

test('diagram action menus expose destructive delete rows with icons', () => {
  const diagramsPage = source('app/diagrams/page.tsx');
  const chartbookPage = source('app/chartbooks/details/page.tsx');

  assert.match(diagramsPage, /label: 'Delete',[\s\S]*icon: <TrashIcon \/>[\s\S]*tone: 'danger'/);
  assert.match(chartbookPage, /label: 'Delete',[\s\S]*icon: <TrashIcon \/>[\s\S]*tone: 'danger'/);
});

test('deleting from a chartbook deletes the diagram and clears both local collections', () => {
  const chartbookPage = source('app/chartbooks/details/page.tsx');
  const deleteHandler = chartbookPage.slice(
    chartbookPage.indexOf('const deleteDiagram = async'),
    chartbookPage.indexOf('const removeFile = async'),
  );

  assert.match(deleteHandler, /agentApi\.deleteDiagram\(ownerId, deletingDiagram\.diagramId\)/);
  assert.match(deleteHandler, /setWorkspaceDiagrams/);
  assert.match(deleteHandler, /setChartbook/);
  assert.match(deleteHandler, /filter\(id => id !== deletingDiagram\.diagramId\)/);
  assert.doesNotMatch(deleteHandler, /window\.confirm/);
});

test('both diagram surfaces use the shared in-product delete confirmation', () => {
  const dialogSource = source('features/diagrams/DiagramDeleteDialog.tsx');
  const diagramsPage = source('app/diagrams/page.tsx');
  const chartbookPage = source('app/chartbooks/details/page.tsx');

  // The dialog follows the existing product overlay, focus, and destructive-action treatments.
  assert.match(dialogSource, /role="alertdialog"/);
  assert.match(dialogSource, /bg-zinc-900\/25[\s\S]*backdrop-blur-sm/);
  assert.match(dialogSource, /cancelButtonRef\.current\?\.focus\(\)/);
  assert.match(dialogSource, /event\.key === 'Escape'/);
  assert.match(dialogSource, /bg-rose-600/);
  assert.match(dialogSource, /Delete diagram/);
  assert.match(diagramsPage, /<DiagramDeleteDialog/);
  assert.match(chartbookPage, /<DiagramDeleteDialog/);

  const workspaceDeleteHandler = diagramsPage.slice(
    diagramsPage.indexOf('const deleteDiagram = async'),
    diagramsPage.indexOf('return ('),
  );
  assert.doesNotMatch(workspaceDeleteHandler, /window\.confirm/);
});
