package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseVersion;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

/** Metadata index for immutable, content-addressed Eval Case versions. */
public interface IEvalCaseVersionStore {
    void insert(EvalCaseVersion value);
    Optional<EvalCaseVersion> find(String caseId, String caseVersion);
    List<EvalCaseVersion> list(String caseId);
    boolean retire(String caseId, String caseVersion, Instant retiredAt);
}
