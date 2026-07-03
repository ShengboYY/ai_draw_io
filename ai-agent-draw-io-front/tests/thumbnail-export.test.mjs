import test from 'node:test';
import assert from 'node:assert/strict';

import {
  THUMBNAIL_EXPORT_OPTIONS,
  buildThumbnailExportRequest,
  isPngThumbnailDataUrl,
  isThumbnailExportResult,
  planThumbnailExport,
  shouldPersistThumbnail,
} from '../src/app/drawio/thumbnail-export.ts';

const pngDataUrl = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB';

test('thumbnail export uses a bounded PNG format', () => {
  assert.equal(THUMBNAIL_EXPORT_OPTIONS.format, 'png');
  assert.equal(THUMBNAIL_EXPORT_OPTIONS.width, '480');
  assert.equal(THUMBNAIL_EXPORT_OPTIONS.border, '12');
  assert.equal(THUMBNAIL_EXPORT_OPTIONS.background, '#ffffff');
});

test('thumbnail export request exports the loaded editor canvas without re-importing XML', () => {
  const request = buildThumbnailExportRequest();

  assert.equal(request.format, 'png');
  assert.equal('xml' in request, false);
  // draw.io answers export requests carrying a non-string `spin` with a `load`
  // event instead of the export payload, which silently kills thumbnails.
  assert.equal('spin' in request, false);
});

test('isPngThumbnailDataUrl accepts png data URLs only', () => {
  assert.equal(isPngThumbnailDataUrl(pngDataUrl), true);
  assert.equal(isPngThumbnailDataUrl('data:image/svg+xml;base64,PHN2Zw=='), false);
  assert.equal(isPngThumbnailDataUrl('https://example.com/thumb.png'), false);
  assert.equal(isPngThumbnailDataUrl(''), false);
});

test('shouldPersistThumbnail requires a diagram id and png data', () => {
  assert.equal(shouldPersistThumbnail({ diagramId: 'diagram-1', dataUrl: pngDataUrl }), true);
  assert.equal(shouldPersistThumbnail({ diagramId: '', dataUrl: pngDataUrl }), false);
  assert.equal(shouldPersistThumbnail({ diagramId: 'diagram-1', dataUrl: 'data:text/plain;base64,SGVsbG8=' }), false);
});

test('isThumbnailExportResult ignores earlier xmlsvg exports while waiting for png', () => {
  assert.equal(isThumbnailExportResult({ format: 'xmlsvg', data: '<mxGraphModel />' }), false);
  assert.equal(isThumbnailExportResult({ format: 'png', data: pngDataUrl }), true);
  assert.equal(isThumbnailExportResult({ data: pngDataUrl }), true);
});

test('planThumbnailExport defers export until the draw.io editor is ready', () => {
  assert.equal(planThumbnailExport({
    diagramId: 'diagram-1',
    hasDrawableContent: true,
    editorReady: false,
  }), 'defer');

  assert.equal(planThumbnailExport({
    diagramId: 'diagram-1',
    hasDrawableContent: true,
    editorReady: true,
  }), 'export');

  assert.equal(planThumbnailExport({
    diagramId: 'diagram-1',
    hasDrawableContent: false,
    editorReady: true,
  }), 'skip');
});
