import type { EvalCaseCandidateDTO, EvalDraftPreparationDTO, SemanticMinerRunDTO } from '@/types/api';

export const ACCEPTANCE_REASON = 'Accepted for sanitized LLM draft preparation';

type CandidateReviewGateway = {
  adminTransitionEvalCandidate: (
    candidateId: string,
    payload: { status: string; reason: string },
  ) => Promise<{ data: EvalCaseCandidateDTO }>;
  adminPrepareEvalDraft: (
    candidateId: string,
    purposeConfirmed: boolean,
  ) => Promise<{ data: EvalDraftPreparationDTO }>;
};

/** Preserve the audited state machine while exposing one human acceptance action to the UI. */
export async function acceptCandidateForDraft(
  gateway: CandidateReviewGateway,
  candidate: Pick<EvalCaseCandidateDTO, 'id' | 'status'>,
): Promise<EvalDraftPreparationDTO> {
  if (candidate.status === 'DETECTED') {
    await gateway.adminTransitionEvalCandidate(candidate.id, {
      status: 'TRIAGED',
      reason: ACCEPTANCE_REASON,
    });
  } else if (candidate.status !== 'TRIAGED') {
    throw new Error(`Candidate status ${candidate.status} cannot prepare a Draft`);
  }
  const response = await gateway.adminPrepareEvalDraft(candidate.id, true);
  return response.data;
}

export function hasActiveSemanticDiscovery(runs: SemanticMinerRunDTO[]): boolean {
  return runs.some((run) => run.status === 'QUEUED' || run.status === 'RUNNING');
}
