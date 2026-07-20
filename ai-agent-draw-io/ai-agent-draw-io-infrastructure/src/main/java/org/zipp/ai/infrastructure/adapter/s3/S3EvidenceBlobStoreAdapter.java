package org.zipp.ai.infrastructure.adapter.s3;

import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.retrieval.port.AuthorizedCandidate;
import org.zipp.ai.domain.retrieval.port.EvidenceBlobStore;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Reads one exact S3 object version and verifies its size/hash through RevisionArtifactPort. */
public final class S3EvidenceBlobStoreAdapter implements EvidenceBlobStore {
    private final RevisionArtifactPort artifacts;

    public S3EvidenceBlobStoreAdapter(RevisionArtifactPort artifacts) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    @Override
    public String readDisplayText(AuthorizedCandidate candidate, long maximumBytes) {
        return new String(artifacts.read(candidate.displayArtifact(), maximumBytes), StandardCharsets.UTF_8);
    }
}
