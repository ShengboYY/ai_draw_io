package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopy;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseWorkingCopyStatus;

import java.util.List;
import java.util.Optional;

/** Persistence seam for mutable case working copies. */
public interface IEvalCaseWorkingCopyStore {
    Optional<EvalCaseWorkingCopy> find(String id);

    List<EvalCaseWorkingCopy> list(EvalCaseWorkingCopyStatus status, String ownerUserId, int limit, int offset);

    void insert(EvalCaseWorkingCopy workingCopy);

    /** Returns false when the expected revision no longer matches. */
    boolean update(EvalCaseWorkingCopy workingCopy, long expectedRevision);
}
