package org.zipp.ai.infrastructure.dao.po.evaluation.controlplane;

import lombok.Data;
import java.util.Date;

@Data
public class EvalDatasetPO {
    private String id;
    private String name;
    private String datasetClass;
    private String evaluationTarget;
    private String ownerUserId;
    private Date createdAt;
}
