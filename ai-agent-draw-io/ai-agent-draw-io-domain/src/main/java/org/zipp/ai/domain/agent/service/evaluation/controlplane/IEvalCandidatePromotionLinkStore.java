package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCandidatePromotionLink;

import java.util.Optional;

/** Restricted persistence port for the post-publication Trace linkage. */
public interface IEvalCandidatePromotionLinkStore {
    Optional<EvalCandidatePromotionLink> findByCandidateId(String candidateId);

    Optional<EvalCandidatePromotionLink> findByWorkingCopyId(String workingCopyId);

    /** Returns the canonical row when concurrent publication attempts race. */
    EvalCandidatePromotionLink insertIfAbsent(EvalCandidatePromotionLink link);
}
