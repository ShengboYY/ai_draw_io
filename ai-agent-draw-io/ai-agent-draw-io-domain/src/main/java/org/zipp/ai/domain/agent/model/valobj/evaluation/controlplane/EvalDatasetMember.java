package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;

/** A dataset pins an exact immutable case version. */
@Value
@Builder
public class EvalDatasetMember {
    String caseId;
    String caseVersion;
}
