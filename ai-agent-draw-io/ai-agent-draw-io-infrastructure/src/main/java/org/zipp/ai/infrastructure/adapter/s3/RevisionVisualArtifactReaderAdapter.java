package org.zipp.ai.infrastructure.adapter.s3;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.multimodal.VisualArtifactReaderPort;

import java.util.Objects;

/** Reuses the S3 exact-version, byte-size and SHA-256 verification used by revision artifacts. */
public final class RevisionVisualArtifactReaderAdapter implements VisualArtifactReaderPort {
    private final RevisionArtifactPort artifacts;

    public RevisionVisualArtifactReaderAdapter(RevisionArtifactPort artifacts) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    @Override
    public byte[] read(StoredArtifact artifact, long maximumBytes) {
        return artifacts.read(artifact, maximumBytes);
    }
}
