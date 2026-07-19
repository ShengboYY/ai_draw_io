package org.zipp.ai.domain.ingestion.model.valobj;

import org.zipp.ai.domain.retrieval.projection.RetrievalProjectionManifest;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Retrieval projection plus every exact S3 object pin required for publication. */
public record RetrievalBuildResult(RetrievalProjectionManifest manifest, StoredArtifact manifestArtifact,
                                   List<RetrievalChunkArtifact> chunkArtifacts) {
    public RetrievalBuildResult {
        manifest = Objects.requireNonNull(manifest, "manifest");
        manifestArtifact = Objects.requireNonNull(manifestArtifact, "manifestArtifact");
        chunkArtifacts = List.copyOf(Objects.requireNonNull(chunkArtifacts, "chunkArtifacts"));
        var chunkIds = manifest.chunks().stream().map(chunk -> chunk.chunkId()).toList();
        var artifactIds = chunkArtifacts.stream().map(RetrievalChunkArtifact::chunkId).toList();
        if (chunkIds.size() != artifactIds.size() || new HashSet<>(artifactIds).size() != artifactIds.size()
                || !new HashSet<>(chunkIds).equals(new HashSet<>(artifactIds))) {
            throw new IllegalArgumentException("every retrieval chunk requires exactly one artifact pin");
        }
    }
}
