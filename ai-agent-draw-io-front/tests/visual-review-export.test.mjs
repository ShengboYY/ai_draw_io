import test from 'node:test';
import assert from 'node:assert/strict';

import {
  VISUAL_REVIEW_RENDERER_VERSION,
  buildVisualReviewExportRequest,
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

test('visual review accepts non-empty PNG data URLs only', () => {
  assert.equal(isVisualReviewPngDataUrl('data:image/png;base64,iVBORw0KGgo='), true);
  assert.equal(isVisualReviewPngDataUrl('data:image/svg+xml;base64,PHN2Zw=='), false);
  assert.equal(isVisualReviewPngDataUrl('data:image/png;base64,'), false);
});
