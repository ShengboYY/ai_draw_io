package org.zipp.ai.infrastructure.dao.po.evaluation;

import lombok.Data;
import java.util.Date;

@Data
public class TraceAnalysisJobPO {
    private String id; private String scope; private String analyzerType; private String analyzerVersion; private String analyzerConfigHash;
    private String sampleDefinitionJson; private Date traceSnapshotAt; private String idempotencyKey;
    private String status; private Integer totalItems; private Integer succeededItems; private Integer failedItems;
    private Double reservedCost; private Double actualCost; private String createdBy;
    private Date createdAt; private Date startedAt; private Date completedAt;
}
