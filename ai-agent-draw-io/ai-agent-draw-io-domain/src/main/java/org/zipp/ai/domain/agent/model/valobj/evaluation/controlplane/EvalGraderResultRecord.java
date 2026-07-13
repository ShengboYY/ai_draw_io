package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;

/** Persistence projection of the existing EvalGraderResult value object. */
@Value
@Builder
public class EvalGraderResultRecord {
    String episodeId;
    String graderName;
    String graderVersion;
    EvalEpisodeStatus status;
    String severity;
    Double score;
    String evidenceJson;
}
