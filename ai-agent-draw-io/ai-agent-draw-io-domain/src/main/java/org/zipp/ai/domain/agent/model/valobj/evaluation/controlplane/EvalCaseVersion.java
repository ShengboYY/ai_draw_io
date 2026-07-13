package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/** Immutable metadata for a published synthetic case artifact. */
@Value
@Builder
public class EvalCaseVersion {
    String caseId;
    String caseVersion;
    String contentHash;
    String artifactRef;
    String approvedBy;
    Instant publishedAt;
    Instant retiredAt;
}
