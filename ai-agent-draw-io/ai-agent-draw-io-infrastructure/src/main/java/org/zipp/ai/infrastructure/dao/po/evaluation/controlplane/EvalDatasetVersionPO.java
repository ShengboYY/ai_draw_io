package org.zipp.ai.infrastructure.dao.po.evaluation.controlplane;

import lombok.Data;
import java.util.Date;

@Data
public class EvalDatasetVersionPO {
    private String datasetId;
    private String version;
    private String datasetClass;
    private String evaluationTarget;
    private String status;
    private String contentHash;
    private Long revision;
    private String publishedBy;
    private Date publishedAt;
}
