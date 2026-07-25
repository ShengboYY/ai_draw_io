import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const read = (path) => readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8');
const library = read('../src/app/library/page.tsx');
const materialDetails = read('../src/app/library/[materialId]/page.tsx');
const chartbooks = read('../src/app/chartbooks/page.tsx');
const chartbookDetails = read('../src/app/chartbooks/[chartbookId]/page.tsx');
const preview = read('../src/features/materials/MaterialPreview.tsx');
const uploader = read('../src/features/materials/MaterialUploader.tsx');
const processingBadge = read('../src/features/materials/MaterialProcessingBadge.tsx');
const gapDialog = read('../src/features/materials/MaterialGapDialog.tsx');
const libraryView = read('../src/features/materials/library-view.ts');
const diagrams = read('../src/app/diagrams/page.tsx');

test('library pages use capability gating, uploader, recycle bin and generic owner errors', () => {
  assert.match(library, /createMaterialCapabilitiesClient/);
  assert.match(library, /MaterialUploader/);
  assert.match(library, /'TRASHED'/);
  assert.match(library, /materialClient\.restore/);
  assert.match(library, /queryInput/);
  assert.match(library, /setOffset/);
  assert.match(materialDetails, /materialAccessMessage/);
  assert.match(materialDetails, /permanentlyDelete/);
  assert.match(materialDetails, /replaceExcludedPages/);
  assert.match(materialDetails, /nativeTextStatus/);
  assert.match(materialDetails, /moveToTrash/);
});

test('material detail renders only a selected page preview and never fetches source content', () => {
  assert.match(materialDetails, /materialClient\.pageSet/);
  assert.match(materialDetails, /<MaterialPreview/);
  assert.match(preview, /selectedPage/);
  assert.match(preview, /materialPreviewUrl/);
  assert.doesNotMatch(preview, /fetch\(/);
});

test('chartbook pages manage shared material and diagram associations through their R1 clients', () => {
  assert.match(chartbooks, /chartbookClient\.create/);
  assert.match(chartbooks, /chartbookClient\.archive/);
  assert.match(chartbookDetails, /chartbookClient\.addMaterial/);
  assert.match(chartbookDetails, /chartbookClient\.assignDiagram/);
  assert.match(chartbookDetails, /MaterialUploader/);
});

test('diagram workspace exposes resource routes from the signed-in account menu', () => {
  assert.match(diagrams, /data-account-menu[\s\S]*href="\/library"[\s\S]*href="\/chartbooks"[\s\S]*Admin dashboard/);
  assert.doesNotMatch(diagrams, /aria-label="Workspace resources"/);
});

test('library and chartbook surfaces use English interface copy', () => {
  // Keep the two resource areas consistent with the English diagram workspace.
  for (const source of [
    library,
    materialDetails,
    chartbooks,
    chartbookDetails,
    preview,
    uploader,
    processingBadge,
    gapDialog,
    libraryView,
  ]) {
    assert.doesNotMatch(source, /[\u3400-\u9fff]/u);
  }
});

test('uploader keeps the current browser-post policy and resumes polling after a retry', () => {
  assert.match(uploader, /postPolicy/);
  assert.match(uploader, /completeAndPoll/);
  assert.match(uploader, /uploadBytesAndComplete/);
});
