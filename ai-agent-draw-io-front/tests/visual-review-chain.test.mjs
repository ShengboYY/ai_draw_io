import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildCanvasVisualReviewRequest,
  canStartPostMutationReview,
  shouldRunFinalVerification,
} from '../src/app/drawio/visual-review-chain.ts';

const base = {
  userId: 'usr-1',
  agentId: '300001',
  sessionId: 'session-1',
  sourceRunId: 'run-1',
  diagramId: 'diagram-1',
  expectedVersion: 3,
  beforeContentHash: 'sha256:before',
  expectedContentHash: 'sha256:abc',
  originalUserTask: 'Draw the checkout flow',
  afterImageDataUrl: 'data:image/png;base64,after',
};

test('post-mutation review is limited to persisted mutation results', () => {
  assert.equal(canStartPostMutationReview({ version: 3, contentHash: 'sha256:abc' }), true);
  assert.equal(canStartPostMutationReview({ version: undefined, contentHash: 'sha256:abc' }), false);
  assert.equal(canStartPostMutationReview({ version: 3, contentHash: '' }), false);
});

test('review requests preserve exact version, hash, stage, and before/after image roles', () => {
  const request = buildCanvasVisualReviewRequest({
    ...base,
    stage: 'POST_MUTATION',
    beforeImageDataUrl: 'data:image/png;base64,before',
  });

  assert.equal(request.expectedVersion, 3);
  assert.equal(request.beforeContentHash, 'sha256:before');
  assert.equal(request.expectedContentHash, 'sha256:abc');
  assert.equal(request.stage, 'POST_MUTATION');
  assert.equal(request.beforeImageDataUrl, 'data:image/png;base64,before');
  assert.equal(request.afterImageDataUrl, 'data:image/png;base64,after');
  assert.equal(request.rendererVersion, 'drawio-embed-png-v1');
});

test('only a repair decision followed by a persisted repaired canvas triggers VERIFY_ONLY', () => {
  assert.equal(shouldRunFinalVerification({ decision: 'REPAIR', version: 4, contentHash: 'sha256:def' }), true);
  assert.equal(shouldRunFinalVerification({ decision: 'APPROVE', version: 4, contentHash: 'sha256:def' }), false);
  assert.equal(shouldRunFinalVerification({ decision: 'REPAIR', version: 4, contentHash: '' }), false);
});
