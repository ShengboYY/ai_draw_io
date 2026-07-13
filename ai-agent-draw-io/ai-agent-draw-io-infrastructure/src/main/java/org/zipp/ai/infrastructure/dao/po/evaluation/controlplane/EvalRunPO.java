package org.zipp.ai.infrastructure.dao.po.evaluation.controlplane;

import lombok.Data;
import java.util.Date;

@Data
public class EvalRunPO {
    private String id; private String mode; private String datasetId; private String datasetVersion;
    private String baselineRef; private String candidateRef; private String executionProfileHash;
    private String idempotencyKey; private Integer repetitions; private Integer plannedEpisodes; private String gitSha;
    private String graderManifestJson; private String reportRef; private String status; private String createdBy;
    private Date createdAt; private Date startedAt; private Date completedAt;
}
