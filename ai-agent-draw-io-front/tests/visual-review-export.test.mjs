import test from 'node:test';
import assert from 'node:assert/strict';

import {
  VISUAL_REVIEW_RENDERER_VERSION,
  buildVisualReviewEvidencePlan,
  buildVisualReviewExportRequest,
  extractDrawioPages,
  isVisualReviewPngDataUrl,
} from '../src/app/drawio/visual-review-export.ts';

test('visual review export uses the production review PNG contract', () => {
  const request = buildVisualReviewExportRequest();

  assert.deepEqual(request, {
    format: 'png',
    width: '1600',
    border: '24',
    background: '#ffffff',
    transparent: false,
  });
  assert.equal(VISUAL_REVIEW_RENDERER_VERSION, 'drawio-embed-png-v1');
});

test('multi-page evidence exports each visible page without mixing page ids', () => {
  const xml = '<mxfile><diagram id="page-a" name="Overview"/><diagram id="page-b" name="Details &amp; Errors"/></mxfile>';

  assert.deepEqual(extractDrawioPages(xml), [
    { pageId: 'page-a', pageName: 'Overview' },
    { pageId: 'page-b', pageName: 'Details & Errors' },
  ]);
  assert.deepEqual(buildVisualReviewEvidencePlan({ canvasXml: xml, nodeCount: 30, edgeCount: 40 }), {
    pages: [
      { pageId: 'page-a', pageName: 'Overview' },
      { pageId: 'page-b', pageName: 'Details & Errors' },
    ],
    totalPageCount: 2,
    truncatedPageCount: 0,
    detailTiles: false,
  });
});

test('dense single-page evidence adds high-resolution detail tiles', () => {
  assert.deepEqual(buildVisualReviewEvidencePlan({
    canvasXml: '<mxGraphModel/>',
    nodeCount: 18,
    edgeCount: 22,
  }), {
    pages: [{ pageName: 'Page-1' }],
    totalPageCount: 1,
    truncatedPageCount: 0,
    detailTiles: true,
  });
});

test('single-page evidence preserves small text with high-resolution detail tiles', () => {
  const plan = buildVisualReviewEvidencePlan({
    canvasXml: '<mxGraphModel><mxCell style="rounded=1;fontSize=9;"/></mxGraphModel>',
    nodeCount: 2,
    edgeCount: 1,
  });

  assert.equal(plan.detailTiles, true);
});

test('page evidence is capped and reports omitted pages', () => {
  const xml = `<mxfile>${Array.from({ length: 6 }, (_, index) => (
    `<diagram id="p${index + 1}" name="Page ${index + 1}"/>`
  )).join('')}</mxfile>`;

  const plan = buildVisualReviewEvidencePlan({ canvasXml: xml, nodeCount: 4, edgeCount: 3 });

  assert.equal(plan.pages.length, 4);
  assert.equal(plan.totalPageCount, 6);
  assert.equal(plan.truncatedPageCount, 2);
  assert.equal(plan.detailTiles, false);
});

test('visual review accepts non-empty PNG data URLs only', () => {
  assert.equal(isVisualReviewPngDataUrl('data:image/png;base64,iVBORw0KGgo='), true);
  assert.equal(isVisualReviewPngDataUrl('data:image/svg+xml;base64,PHN2Zw=='), false);
  assert.equal(isVisualReviewPngDataUrl('data:image/png;base64,'), false);
});
