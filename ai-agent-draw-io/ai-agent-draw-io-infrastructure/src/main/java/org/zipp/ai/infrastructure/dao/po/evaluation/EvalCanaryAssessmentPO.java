package org.zipp.ai.infrastructure.dao.po.evaluation;

import lombok.Data;
import java.util.Date;

@Data
public class EvalCanaryAssessmentPO {
    private String id; private String evalRunId; private String deploymentRef; private String policyVersion;
    private String outcome; private String reasonsJson; private Integer baselineRequests; private Integer canaryRequests;
    private Integer canaryFailures; private Integer criticalFindings; private Integer infrastructureErrors;
    private Double p95LatencyMs; private Double averageCost; private String createdBy; private Date createdAt;
}
