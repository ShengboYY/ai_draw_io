package org.zipp.ai.infrastructure.dao.po.evaluation;

import lombok.Data;

import java.util.Date;

@Data
public class EvalCaseHealthPO {
    private String caseId;
    private String caseVersion;
    private Boolean baselineReproduced;
    private String healthStatus;
    private String summary;
    private Date updatedAt;
}
