package org.zipp.ai.infrastructure.dao.po.evaluation.controlplane;

import lombok.Data;
import java.util.Date;

@Data
public class EvalCaseVersionPO {
    private String caseId;
    private String caseVersion;
    private String contentHash;
    private String artifactRef;
    private String evaluationTarget;
    private String targetMigrationStatus;
    private String approvedBy;
    private Date publishedAt;
    private Date retiredAt;
}
