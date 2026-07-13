package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalTrace;

import java.util.List;

/** Authorized projection of a synthetic Mode B execution artifact. */
@Value @Builder
public class EvalEpisodeArtifactView {
    EvalTrace trace;
    String initialCanvasXml;
    String finalCanvasXml;
    String initialCanvasImageDataUrl;
    String finalCanvasImageDataUrl;
    List<String> semanticDiff;
}
