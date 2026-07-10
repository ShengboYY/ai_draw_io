package org.zipp.ai.domain.agent.service.evaluation.intake;

import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseLineage;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseReview;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus;

import java.util.Optional;

/** Persistence boundary for P0 intake. Debug payloads and approved fixture contents are out of scope. */
public interface ITraceToEvalStore {
    Optional<EvalCaseCandidate> findCandidate(String candidateId);
    Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String sourceRunId, String failureFamily);
    void insertCandidate(EvalCaseCandidate candidate);
    void updateCandidateStatus(String candidateId, EvalCandidateStatus status);
    void insertReview(EvalCaseReview review);
    void insertLineage(EvalCaseLineage lineage);
}
