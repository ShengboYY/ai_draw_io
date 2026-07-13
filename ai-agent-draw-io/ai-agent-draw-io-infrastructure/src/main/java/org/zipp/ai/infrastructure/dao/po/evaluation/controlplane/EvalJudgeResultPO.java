package org.zipp.ai.infrastructure.dao.po.evaluation.controlplane;

import lombok.Data;

@Data
public class EvalJudgeResultPO {
    private String episodeId; private String judgeVersion; private String calibrationVersion;
    private String status; private String scoreJson; private String evidenceJson;
}
