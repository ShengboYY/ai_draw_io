package org.zipp.ai.domain.retrieval.projection;

import org.zipp.ai.domain.retrieval.model.valobj.VectorProjectionRole;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable recovery/audit manifest for one revision in one index generation. */
public record VectorProjectionManifest(String schemaVersion, String revisionId, String versionId,
                                       String generationId, String indexName, String namespace,
                                       String embeddingModel, String embeddingModelFingerprint,
                                       int dimension, String metric, String vectorSchemaVersion,
                                       String tokenizerFingerprint, VectorProjectionRole projectionRole,
                                       List<VectorProjectionManifestEntry> entries, String manifestHash) {
    public VectorProjectionManifest {
        if (schemaVersion == null || schemaVersion.isBlank() || revisionId == null || revisionId.isBlank()
                || versionId == null || versionId.isBlank() || generationId == null || generationId.isBlank()
                || indexName == null || indexName.isBlank() || namespace == null || namespace.isBlank()
                || embeddingModel == null || embeddingModel.isBlank()
                || embeddingModelFingerprint == null || !embeddingModelFingerprint.matches("[0-9a-f]{64}")
                || dimension < 1 || metric == null || metric.isBlank()
                || vectorSchemaVersion == null || vectorSchemaVersion.isBlank()
                || tokenizerFingerprint == null || tokenizerFingerprint.isBlank()
                || projectionRole == null
                || manifestHash == null || !manifestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("vector projection manifest identity is invalid");
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (new HashSet<>(entries.stream().map(VectorProjectionManifestEntry::chunkId).toList()).size()
                != entries.size()) {
            throw new IllegalArgumentException("vector projection manifest entries must be unique");
        }
    }

    public static VectorProjectionManifest create(String revisionId, String versionId,
                                                   VectorGenerationProfile profile,
                                                   VectorProjectionRole projectionRole,
                                                   List<VectorProjectionManifestEntry> entries) {
        VectorGenerationProfile generation = Objects.requireNonNull(profile, "profile");
        List<VectorProjectionManifestEntry> ordered = List.copyOf(entries).stream()
                .sorted(Comparator.comparing(VectorProjectionManifestEntry::chunkId)).toList();
        String hash = VectorGenerationProfile.sha256("vector-projection-manifest-v1:" + revisionId + ":"
                + versionId + ":" + generation.generationId() + ":" + generation.generationFingerprint()
                + ":" + generation.tokenizerFingerprint() + ":" + projectionRole + ":" + ordered);
        return new VectorProjectionManifest("vector-projection-manifest-v1", revisionId, versionId,
                generation.generationId(), generation.indexName(), generation.namespace(),
                generation.embeddingModel(), generation.embeddingModelFingerprint(), generation.dimension(),
                generation.metric(), generation.vectorSchemaVersion(), generation.tokenizerFingerprint(),
                projectionRole, ordered, hash);
    }
}
