package org.zipp.ai.infrastructure.dao.po.evaluation;

import lombok.Data;
import java.util.Date;

@Data
public class EvalCaseCandidatePO {
    private String id; private String sourceRunId; private String sourceSpanId; private String sourcePhase; private String sourceAgentId;
    private String failureFamily; private String ruleId; private String evidenceSummary; private String risk; private Date discoveredAt;
    private String policyVersion; private String status; private String createdBy;
}
