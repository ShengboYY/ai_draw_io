package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopy;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopyStatus;

import java.util.List;
import java.util.Optional;

/** Persistence seam for mutable case working copies. */
public interface IEvalCaseWorkingCopyStore {
    Optional<EvalCaseWorkingCopy> find(String id);

    /** Candidate is the only Draft-stage backlink from Evaluation to restricted Trace data. */
    default Optional<EvalCaseWorkingCopy> findByCandidateId(String candidateId) {
        return Optional.empty();
    }

    List<EvalCaseWorkingCopy> list(EvalCaseWorkingCopyStatus status, String ownerUserId, int limit, int offset);

    void insert(EvalCaseWorkingCopy workingCopy);

    /** Atomically returns the canonical Working Copy when concurrent Promote requests race. */
    default EvalCaseWorkingCopy insertTraceDraftIfAbsent(EvalCaseWorkingCopy workingCopy) {
        Optional<EvalCaseWorkingCopy> existing = findByCandidateId(workingCopy.getCandidateId());
        if (existing.isPresent()) return existing.get();
        insert(workingCopy);
        return workingCopy;
    }

    /** Returns false when the expected revision no longer matches. */
    boolean update(EvalCaseWorkingCopy workingCopy, long expectedRevision);
}
