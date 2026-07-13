package org.zipp.ai.infrastructure.dao.po.evaluation.controlplane;

import lombok.Data;

@Data
public class EvalDatasetMemberPO {
    private String datasetId;
    private String datasetVersion;
    private String caseId;
    private String caseVersion;
}
