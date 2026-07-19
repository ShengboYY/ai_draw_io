package org.zipp.ai.ingestion.worker.document;

/** Versioned memory and complexity limits shared by evidence routing and execution. */
public record EvidenceBuildLimits(long maximumArtifactUncompressedBytes,
                                  long maximumDocumentCharacters,
                                  long maximumDocumentRegions) {
    public EvidenceBuildLimits {
        if (maximumArtifactUncompressedBytes < 1 || maximumDocumentCharacters < 1
                || maximumDocumentRegions < 1) {
            throw new IllegalArgumentException("evidence build limits must be positive");
        }
    }

    public String fingerprint() {
        return "evidence-limits-v1:max-artifact-uncompressed-bytes=" + maximumArtifactUncompressedBytes
                + ":max-document-characters=" + maximumDocumentCharacters
                + ":max-document-regions=" + maximumDocumentRegions;
    }
}
