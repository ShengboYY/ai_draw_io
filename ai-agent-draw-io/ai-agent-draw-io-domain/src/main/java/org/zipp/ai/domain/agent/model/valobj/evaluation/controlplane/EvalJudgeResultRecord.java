package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;

/** Persistence projection of one existing EvalJudgeResult. */
@Value
@Builder
public class EvalJudgeResultRecord {
    String episodeId;
    String judgeVersion;
    String calibrationVersion;
    EvalEpisodeStatus status;
    String scoreJson;
    String evidenceJson;
}
