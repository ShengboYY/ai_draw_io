import test from 'node:test';
import assert from 'node:assert/strict';

import {
  materialAccessMessage,
  materialCapabilityMessage,
  materialPreviewUrl,
} from '../src/features/materials/library-view.ts';

test('material library presents disabled catalog capability without retry guidance', () => {
  assert.equal(
    materialCapabilityMessage({ catalog: 'DISABLED', preview: 'DISABLED' }),
    'The library is currently unavailable. Contact an administrator to enable it.',
  );
});

test('material library does not distinguish absent resources from unauthorized resources', () => {
  assert.equal(materialAccessMessage('MATERIAL_NOT_FOUND'), 'This item does not exist or you do not have access.');
  assert.equal(materialAccessMessage('HTTP_ERROR'), 'This item does not exist or you do not have access.');
});

test('material preview URL targets a single page without reading file content', () => {
  assert.equal(
    materialPreviewUrl('https://app.example/api/v1', 'mat 1', 'ver/1', 2),
    'https://app.example/api/v1/materials/mat%201/versions/ver%2F1/pages/2/preview',
  );
});
