package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import java.util.Optional;

/** Controlled storage for normalized trace/canvas/report artifacts, separate from production Debug Trace. */
public interface IEvalRunArtifactStore {
    String put(String runId, String episodeId, String artifactType, byte[] content);
    Optional<byte[]> read(String artifactRef);
}
