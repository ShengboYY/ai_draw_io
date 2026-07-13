import test from 'node:test';
import assert from 'node:assert/strict';
import {
  acceptCandidateForDraft,
  hasActiveSemanticDiscovery,
} from '../src/app/admin/eval-candidates/candidate-review-workflow.ts';

test('human acceptance hides TRIAGED but preserves the audited transition before Draft generation', async () => {
  const calls = [];
  const gateway = {
    adminTransitionEvalCandidate: async (candidateId, payload) => {
      calls.push(['transition', candidateId, payload.status, payload.reason]);
      return { data: { id: candidateId, status: payload.status } };
    },
    adminPrepareEvalDraft: async (candidateId, purposeConfirmed) => {
      calls.push(['draft', candidateId, purposeConfirmed]);
      return { data: { status: 'DRAFT_READY', sanitizerEvidence: [] } };
    },
  };

  const result = await acceptCandidateForDraft(gateway, { id: 'candidate-1', status: 'DETECTED' });

  assert.equal(result.status, 'DRAFT_READY');
  assert.deepEqual(calls, [
    ['transition', 'candidate-1', 'TRIAGED', 'Accepted for sanitized LLM draft preparation'],
    ['draft', 'candidate-1', true],
  ]);
});

test('a TRIAGED Candidate retries Draft generation without repeating the transition', async () => {
  const calls = [];
  const gateway = {
    adminTransitionEvalCandidate: async () => { throw new Error('transition must not repeat'); },
    adminPrepareEvalDraft: async (candidateId, purposeConfirmed) => {
      calls.push([candidateId, purposeConfirmed]);
      return { data: { status: 'NEEDS_MANUAL_RECONSTRUCTION', sanitizerEvidence: ['model_or_schema_failure'] } };
    },
  };

  const result = await acceptCandidateForDraft(gateway, { id: 'candidate-2', status: 'TRIAGED' });

  assert.equal(result.status, 'NEEDS_MANUAL_RECONSTRUCTION');
  assert.deepEqual(calls, [['candidate-2', true]]);
});

test('only queued or running discovery jobs keep inbox polling active', () => {
  assert.equal(hasActiveSemanticDiscovery([{ status: 'COMPLETED' }]), false);
  assert.equal(hasActiveSemanticDiscovery([{ status: 'RUNNING' }]), true);
  assert.equal(hasActiveSemanticDiscovery([{ status: 'QUEUED' }]), true);
});
