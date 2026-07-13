package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Restricted, short-lived metadata record. It may reference a source run but never contains payloads. */
@Data
@Builder
public class EvalCaseCandidate {
    private String id;
    private String sourceRunId;
    private String sourceSpanId;
    private String sourcePhase;
    private String sourceAgentId;
    private String failureFamily;
    private String ruleId;
    private String evidenceSummary;
    private String risk;
    private Instant discoveredAt;
    private String policyVersion;
    private EvalCandidateStatus status;
    private String createdBy;
    /** MANUAL, RULE_DETECTED, or MODEL_DETECTED. */
    private String detectionSource;
    private String modelVersion;
    private Double modelConfidence;
    @Builder.Default
    private java.util.List<String> modelEvidence = java.util.List.of();
}
