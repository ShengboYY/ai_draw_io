package org.zipp.ai.infrastructure.dao.po.evaluation.controlplane;

import lombok.Data;

import java.util.Date;

@Data
public class EvalCandidatePromotionLinkPO {
    private String candidateId;
    private String workingCopyId;
    private String caseId;
    private String caseVersion;
    private Date promotedAt;
    private Date retentionExpiresAt;
}
