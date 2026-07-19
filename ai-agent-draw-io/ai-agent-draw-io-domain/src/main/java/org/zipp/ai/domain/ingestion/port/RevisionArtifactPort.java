package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;

import java.nio.file.Path;
import java.util.Optional;

public interface RevisionArtifactPort {
    StoredArtifact putImmutable(String objectKey, byte[] content, String contentType);
    /** Finds the current immutable object pin without reading its bounded content. */
    default Optional<StoredArtifact> findImmutable(String objectKey, String contentType, long maximumBytes) {
        return Optional.empty();
    }
    /** Best-effort removal of one exact object version, used only for disposable corrupted derivatives. */
    default boolean deleteExact(StoredArtifact artifact) { return false; }
    byte[] read(StoredArtifact artifact, long maximumBytes);
    Path download(StoredArtifact artifact, long maximumBytes, Path destination);
}
