package org.zipp.ai.domain.retrieval.model.valobj;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionState;
import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;
import org.zipp.ai.domain.retrieval.projection.VectorProjectionManifestEntry;

import java.util.List;
import java.util.Objects;

/** Exact publication snapshot for one revision and one vector generation. */
public record RevisionPublicationWork(RevisionProjectionContext context,
                                      VectorGenerationProfile profile,
                                      StoredArtifact structureArtifact,
                                      StoredArtifact evidenceManifestArtifact,
                                      StoredArtifact gapManifestArtifact,
                                      StoredArtifact projectionManifestArtifact,
                                      String projectionManifestHash,
                                      int retrievalChunkCount,
                                      int lexicalProjectionCount,
                                      int exactTermCount,
                                      int chunkEvidenceMappingCount,
                                      int evidenceUnitCount,
                                      int expectedProjectionCount,
                                      int indexedProjectionCount,
                                      IndexGenerationState generationState,
                                      String activeGenerationId,
                                      List<VectorProjectionManifestEntry> indexedProjections) {
    public RevisionPublicationWork {
        context = Objects.requireNonNull(context, "context");
        profile = Objects.requireNonNull(profile, "profile");
        structureArtifact = Objects.requireNonNull(structureArtifact, "structureArtifact");
        evidenceManifestArtifact = Objects.requireNonNull(evidenceManifestArtifact, "evidenceManifestArtifact");
        projectionManifestArtifact = Objects.requireNonNull(
                projectionManifestArtifact, "projectionManifestArtifact");
        if (projectionManifestHash == null || !projectionManifestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("projectionManifestHash must be lowercase SHA-256");
        }
        if (retrievalChunkCount < 0 || lexicalProjectionCount < 0 || exactTermCount < 0
                || chunkEvidenceMappingCount < 0 || evidenceUnitCount < 0
                || expectedProjectionCount < 0 || indexedProjectionCount < 0) {
            throw new IllegalArgumentException("projection counts cannot be negative");
        }
        generationState = Objects.requireNonNull(generationState, "generationState");
        indexedProjections = List.copyOf(Objects.requireNonNull(indexedProjections, "indexedProjections"));
        if (indexedProjections.stream().map(VectorProjectionManifestEntry::chunkId).distinct().count()
                != indexedProjections.size()
                || indexedProjections.stream().map(VectorProjectionManifestEntry::vectorId).distinct().count()
                != indexedProjections.size()) {
            throw new IllegalArgumentException("indexed projections must have unique chunk and vector ids");
        }
    }

    public List<String> vectorIds() {
        return indexedProjections.stream().map(VectorProjectionManifestEntry::vectorId).toList();
    }

    public ProcessingRevisionState publicationState() {
        return gapManifestArtifact == null
                ? ProcessingRevisionState.READY : ProcessingRevisionState.PARTIAL_READY;
    }

    public String publicationInputFingerprint() {
        return publicationInputFingerprint(
                projectionManifestArtifact.contentSha256(), projectionManifestHash);
    }

    public static String publicationWorkKey(String generationId) {
        if (generationId == null || generationId.isBlank()) {
            throw new IllegalArgumentException("generationId is required");
        }
        return "ig:" + generationId.trim();
    }

    public static String publicationInputFingerprint(String artifactSha256, String manifestHash) {
        if (artifactSha256 == null || !artifactSha256.matches("[0-9a-f]{64}")
                || manifestHash == null || !manifestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("publication hashes must be lowercase SHA-256");
        }
        return VectorGenerationProfile.sha256(
                artifactSha256 + ":" + manifestHash + ":PUBLISH_REVISION");
    }
}
