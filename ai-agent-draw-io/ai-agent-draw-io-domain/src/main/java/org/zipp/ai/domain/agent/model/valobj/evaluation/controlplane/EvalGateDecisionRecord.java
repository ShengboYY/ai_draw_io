package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/** Persistence projection of a release-gate decision. */
@Value
@Builder
public class EvalGateDecisionRecord {
    String evalRunId;
    String gateVersion;
    EvalGateOutcome outcome;
    String reasonsJson;
    Instant decidedAt;
}
