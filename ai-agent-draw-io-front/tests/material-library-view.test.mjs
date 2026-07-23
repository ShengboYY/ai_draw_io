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
    '资料库当前不可用。请联系管理员开启资料库功能。',
  );
});

test('material library does not distinguish absent resources from unauthorized resources', () => {
  assert.equal(materialAccessMessage('MATERIAL_NOT_FOUND'), '资料不存在或你无权访问。');
  assert.equal(materialAccessMessage('HTTP_ERROR'), '资料不存在或你无权访问。');
});

test('material preview URL targets a single page without reading file content', () => {
  assert.equal(
    materialPreviewUrl('https://app.example/api/v1', 'mat 1', 'ver/1', 2),
    'https://app.example/api/v1/materials/mat%201/versions/ver%2F1/pages/2/preview',
  );
});
