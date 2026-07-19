package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;

import java.nio.file.Path;

public interface RevisionArtifactPort {
    StoredArtifact putImmutable(String objectKey, byte[] content, String contentType);
    byte[] read(StoredArtifact artifact, long maximumBytes);
    Path download(StoredArtifact artifact, long maximumBytes, Path destination);
}
