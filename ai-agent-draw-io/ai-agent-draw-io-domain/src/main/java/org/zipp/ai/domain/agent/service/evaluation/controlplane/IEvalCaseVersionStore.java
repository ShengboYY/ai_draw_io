package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseVersion;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;

/** Metadata index for immutable, content-addressed Eval Case versions. */
public interface IEvalCaseVersionStore {
    void insert(EvalCaseVersion value);
    Optional<EvalCaseVersion> find(String caseId, String caseVersion);
    List<EvalCaseVersion> list(String caseId);
    boolean retire(String caseId, String caseVersion, Instant retiredAt);
    default boolean confirmTarget(String caseId, String caseVersion, EvaluationTarget target) { return false; }
}
