package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;

@FunctionalInterface
public interface VisualArtifactReaderPort {
    byte[] read(StoredArtifact artifact, long maximumBytes);
}
