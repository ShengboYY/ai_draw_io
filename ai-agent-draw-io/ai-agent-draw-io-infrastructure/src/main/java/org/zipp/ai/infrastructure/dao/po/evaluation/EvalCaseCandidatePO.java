package org.zipp.ai.infrastructure.dao.po.evaluation;

import lombok.Data;
import java.util.Date;

@Data
public class EvalCaseCandidatePO {
    private String id; private String sourceRunId; private String sourceSpanId; private String sourcePhase; private String sourceAgentId;
    private String failureFamily; private String ruleId; private String evidenceSummary; private String risk; private Date discoveredAt;
    private String policyVersion; private String status; private String createdBy;
    private String detectionSource; private String modelVersion; private Double modelConfidence; private String modelEvidenceJson;
    // Populated only by the Findings projection query; never written back to Candidate.
    private String routeType; private String findingAgentId; private Long sourceLatencyMs;
    private String reviewedBy; private Date reviewedAt;
}
