import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildCanvasVisualReviewRequest,
  canStartPostMutationReview,
  shouldShowUnavailableReview,
  shouldRunFinalVerification,
} from '../src/app/drawio/visual-review-chain.ts';

const base = {
  userId: 'usr-1',
  agentId: '300001',
  sessionId: 'session-1',
  sourceRunId: 'run-1',
  parentRunId: 'run-1',
  visualRepairRound: 0,
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
  assert.equal(request.sourceRunId, 'run-1');
  assert.equal(request.parentRunId, 'run-1');
  assert.equal(request.visualRepairRound, 0);
  assert.equal(request.beforeImageDataUrl, 'data:image/png;base64,before');
  assert.equal(request.afterImageDataUrl, 'data:image/png;base64,after');
  assert.equal(request.rendererVersion, 'drawio-embed-png-v1');
});

test('only a repair decision followed by a persisted repaired canvas triggers VERIFY_ONLY', () => {
  const reviewed = { reviewedVersion: 3, reviewedContentHash: 'sha256:abc' };
  assert.equal(shouldRunFinalVerification({
    ...reviewed, decision: 'REPAIR', version: 4, contentHash: 'sha256:def',
  }), true);
  assert.equal(shouldRunFinalVerification({
    ...reviewed, decision: 'APPROVE', version: 4, contentHash: 'sha256:def',
  }), false);
  assert.equal(shouldRunFinalVerification({
    ...reviewed, decision: 'REPAIR', version: 3, contentHash: 'sha256:def',
  }), false);
  assert.equal(shouldRunFinalVerification({
    ...reviewed, decision: 'REPAIR', version: 4, contentHash: 'sha256:abc',
  }), false);
});

test('repair transport failures preserve an already presented VLM review', () => {
  assert.equal(shouldShowUnavailableReview(false), true);
  assert.equal(shouldShowUnavailableReview(true), false);
});

test('later review-chain failures do not replace POST_MUTATION findings', () => {
  const visualReviews = [{ stage: 'POST_MUTATION', decision: 'REPAIR' }];
  const hasPostMutationReview = visualReviews.some(review => review.stage === 'POST_MUTATION');

  assert.equal(shouldShowUnavailableReview(hasPostMutationReview), false);
});
