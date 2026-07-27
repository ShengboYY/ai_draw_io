import test from 'node:test';
import assert from 'node:assert/strict';

import { citationOriginLabel } from '../src/app/drawio/citation-origin.ts';

test('citationOriginLabel presents direct attachment cells as original image evidence', () => {
  assert.equal(citationOriginLabel('DIRECT_ATTACHMENT'), 'original image');
});

test('citationOriginLabel keeps existing origin values readable', () => {
  assert.equal(citationOriginLabel('EXISTING_REFERENCE'), 'existing reference');
  assert.equal(citationOriginLabel('SUPPLEMENTAL'), 'supplemental');
});
