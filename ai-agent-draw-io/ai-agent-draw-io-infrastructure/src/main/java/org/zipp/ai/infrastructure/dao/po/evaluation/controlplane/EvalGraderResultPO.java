package org.zipp.ai.infrastructure.dao.po.evaluation.controlplane;

import lombok.Data;

@Data
public class EvalGraderResultPO {
    private String episodeId; private String graderName; private String graderVersion; private String status;
    private String severity; private Double score; private String evidenceJson;
}
