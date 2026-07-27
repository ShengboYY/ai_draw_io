import test from 'node:test';
import assert from 'node:assert/strict';

import {
  createUploadState,
  transitionUpload,
} from '../src/features/materials/upload-machine.ts';

test('upload machine advances one file from hashing through backend processing', () => {
  let state = createUploadState('guide.pdf');
  state = transitionUpload(state, { type: 'START' });
  state = transitionUpload(state, { type: 'HASHED' });
  state = transitionUpload(state, { type: 'INITIATED', uploadId: 'upl-1' });
  state = transitionUpload(state, { type: 'BYTES_UPLOADED' });
  state = transitionUpload(state, { type: 'COMPLETED', status: 'PROCESSING' });

  assert.equal(state.stage, 'PROCESSING');
  assert.equal(state.uploadId, 'upl-1');
  assert.equal(state.retryable, false);
});

test('upload machine preserves a partial-ready gap and retries the failed step only', () => {
  let state = createUploadState('diagram.png');
  state = transitionUpload(state, { type: 'START' });
  state = transitionUpload(state, { type: 'HASHED' });
  state = transitionUpload(state, { type: 'FAILED', message: 'network unavailable' });
  state = transitionUpload(state, { type: 'RETRY' });
  state = transitionUpload(state, { type: 'INITIATED', uploadId: 'upl-2' });
  state = transitionUpload(state, { type: 'BYTES_UPLOADED' });
  state = transitionUpload(state, { type: 'COMPLETED', status: 'PARTIAL_READY', errorCode: 'OCR_GAP' });

  assert.equal(state.stage, 'PARTIAL_READY');
  assert.equal(state.gapCode, 'OCR_GAP');
  assert.equal(state.retryable, false);
});

test('upload machine retries completion and retains rejected terminal status', () => {
  let state = createUploadState('scan.jpg');
  state = transitionUpload(state, { type: 'START' });
  state = transitionUpload(state, { type: 'HASHED' });
  state = transitionUpload(state, { type: 'INITIATED', uploadId: 'upl-3' });
  state = transitionUpload(state, { type: 'BYTES_UPLOADED' });
  state = transitionUpload(state, { type: 'FAILED', message: 'complete endpoint timed out' });

  assert.equal(state.stage, 'FAILED');
  assert.equal(state.retryStage, 'COMPLETING');

  state = transitionUpload(state, { type: 'RETRY' });
  state = transitionUpload(state, { type: 'COMPLETED', status: 'REJECTED', errorCode: 'MALWARE_DETECTED' });

  assert.equal(state.stage, 'REJECTED');
  assert.equal(state.errorMessage, 'MALWARE_DETECTED');
  assert.equal(state.retryable, false);
});

test('upload machine ignores events that do not match the current stage', () => {
  const idle = createUploadState('guide.pdf');

  assert.equal(transitionUpload(idle, { type: 'INITIATED', uploadId: 'upl-invalid' }), idle);
  assert.equal(transitionUpload(idle, { type: 'COMPLETED', status: 'READY' }), idle);
});

test('upload machine presents a succeeded upload session as ready', () => {
  let state = createUploadState('diagram.png');
  state = transitionUpload(state, { type: 'START' });
  state = transitionUpload(state, { type: 'HASHED' });
  state = transitionUpload(state, { type: 'INITIATED', uploadId: 'upl-success' });
  state = transitionUpload(state, { type: 'BYTES_UPLOADED' });
  state = transitionUpload(state, { type: 'COMPLETED', status: 'SUCCEEDED' });

  assert.equal(state.stage, 'READY');
});
