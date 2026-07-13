package org.zipp.ai.domain.agent.service.evaluation.intake;

import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseLineage;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseReview;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseDraft;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseHealthRecord;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.SemanticMinerRun;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceFindingFilter;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceFindingCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceFindingContext;

import java.util.Optional;
import java.util.List;

/** Persistence boundary for P0 intake. Debug payloads and approved fixture contents are out of scope. */
public interface ITraceToEvalStore {
    Optional<EvalCaseCandidate> findCandidate(String candidateId);
    Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String sourceRunId, String failureFamily);
    default List<EvalCaseCandidate> listCandidates(EvalCandidateStatus status, String risk, int limit, int offset) {
        return List.of();
    }
    default List<TraceFindingCandidate> listFindingCandidates(TraceFindingFilter filter) {
        return listCandidates(filter.status(), filter.risk(), filter.limit(), filter.offset()).stream()
                .map(candidate -> new TraceFindingCandidate(candidate,
                        new TraceFindingContext(null, candidate.getSourceAgentId(), null, null, null)))
                .toList();
    }
    default Optional<TraceFindingCandidate> findFindingCandidate(String candidateId) {
        return findCandidate(candidateId).map(candidate -> new TraceFindingCandidate(candidate,
                new TraceFindingContext(null, candidate.getSourceAgentId(), null, null, null)));
    }
    void insertCandidate(EvalCaseCandidate candidate);
    default void mergeCandidateModelEvidence(String candidateId, String modelVersion, Double confidence,
                                             List<String> evidence, String evidenceSummary) { }
    void updateCandidateStatus(String candidateId, EvalCandidateStatus status);
    void insertReview(EvalCaseReview review);
    default Optional<EvalCaseReview> findLatestReview(String candidateId) { return Optional.empty(); }
    default Optional<EvalCaseDraft> findLatestDraft(String candidateId) { return Optional.empty(); }
    default void insertDraft(EvalCaseDraft draft) { }
    default void upsertCaseHealth(EvalCaseHealthRecord health) { }
    default List<EvalCaseHealthRecord> listCaseHealth(String status, int limit) { return List.of(); }
    default void insertSemanticMinerRun(SemanticMinerRun run) { }
    default void updateSemanticMinerRun(SemanticMinerRun run) { }
    default Optional<SemanticMinerRun> findSemanticMinerRun(String runId) { return Optional.empty(); }
    default List<SemanticMinerRun> listSemanticMinerRuns(int limit) { return List.of(); }
    void insertLineage(EvalCaseLineage lineage);
}
