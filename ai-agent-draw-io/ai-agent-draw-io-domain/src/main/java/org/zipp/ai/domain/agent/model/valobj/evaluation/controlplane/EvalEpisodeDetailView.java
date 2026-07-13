package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/** On-demand synthetic Case evidence; large canvas artifacts use a separate endpoint. */
@Value @Builder
public class EvalEpisodeDetailView {
    EvalEpisodeView episode;
    Map<String, Object> input;
    Object expected;
}
