import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildSourceUseOverride,
  hasSingleReadyImageSelection,
} from '../src/features/sources/source-intent.ts';

test('source use stays automatic unless the user explicitly overrides it', () => {
  assert.deepEqual(buildSourceUseOverride('AUTO', true), {});
  assert.deepEqual(buildSourceUseOverride('DIRECT', true), { sourceUseOverride: 'DIRECT' });
  assert.deepEqual(buildSourceUseOverride('DIRECT_AND_RETRIEVAL', true), {
    sourceUseOverride: 'DIRECT_AND_RETRIEVAL',
  });
});

test('source use override is suppressed without one selected ready image', () => {
  const attachments = [
    { uploadId: 'image-ready', fileName: 'flow.png', state: 'READY' },
    { uploadId: 'guide-ready', fileName: 'guide.pdf', state: 'READY' },
    { uploadId: 'image-processing', fileName: 'draft.jpg', state: 'PROCESSING' },
  ];

  assert.equal(hasSingleReadyImageSelection(attachments, ['image-ready']), true);
  assert.equal(hasSingleReadyImageSelection(attachments, ['guide-ready']), false);
  assert.equal(hasSingleReadyImageSelection(attachments, ['image-processing']), false);
  assert.equal(hasSingleReadyImageSelection(attachments, ['image-ready', 'guide-ready']), false);
  assert.deepEqual(buildSourceUseOverride('DIRECT', false), {});
});
