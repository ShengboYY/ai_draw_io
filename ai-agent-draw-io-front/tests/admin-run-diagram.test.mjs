import test from 'node:test';
import assert from 'node:assert/strict';

import { diagramPreviewTitle, diagramPreviewMeta, diagramPreviewHasImage } from '../src/app/admin/admin-shared.ts';

test('diagramPreviewTitle prefers title and falls back to shortened diagram id', () => {
  assert.equal(
    diagramPreviewTitle({ diagramId: 'diag_1234567890abcdef', title: ' Checkout flow ' }),
    'Checkout flow',
  );
  assert.equal(
    diagramPreviewTitle({ diagramId: 'diag_1234567890abcdef' }),
    'diag_123456…',
  );
});

test('diagramPreviewMeta summarizes version and update time without inventing data', () => {
  assert.equal(
    diagramPreviewMeta({ version: 4, updatedAt: '2026-07-03T09:05:00.000Z' }),
    'v4 · 2026-07-03 09:05 UTC',
  );
  assert.equal(diagramPreviewMeta({}), 'No saved canvas snapshot');
});

test('diagramPreviewHasImage only enables zoom when a thumbnail exists', () => {
  assert.equal(diagramPreviewHasImage({ thumbnailUrl: ' data:image/png;base64,abc ' }), true);
  assert.equal(diagramPreviewHasImage({ thumbnailUrl: '   ' }), false);
  assert.equal(diagramPreviewHasImage(null), false);
});
