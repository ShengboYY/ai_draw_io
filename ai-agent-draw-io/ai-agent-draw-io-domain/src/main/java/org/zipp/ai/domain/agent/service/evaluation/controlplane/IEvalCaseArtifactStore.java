package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import java.util.Optional;

/** Immutable content-addressed storage for synthetic case artifacts. */
public interface IEvalCaseArtifactStore {
    String putIfAbsent(String contentHash, byte[] content);

    Optional<byte[]> read(String artifactRef);
}
