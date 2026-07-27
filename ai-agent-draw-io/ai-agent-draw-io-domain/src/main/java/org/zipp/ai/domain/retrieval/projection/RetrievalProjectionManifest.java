package org.zipp.ai.domain.retrieval.projection;

import java.util.List;
import java.util.Objects;
import java.util.HashSet;

public record RetrievalProjectionManifest(String schemaVersion, String revisionId, String versionId,
                                          String evidenceHash, String builderFingerprint,
                                          List<RetrievalChunkProjection> chunks,
                                          List<LexicalProjection> lexicalProjections,
                                          String projectionHash) {
    public RetrievalProjectionManifest {
        if (schemaVersion == null || schemaVersion.isBlank() || revisionId == null || revisionId.isBlank()
                || versionId == null || versionId.isBlank()
                || evidenceHash == null || !evidenceHash.matches("[0-9a-f]{64}")
                || builderFingerprint == null || builderFingerprint.isBlank()
                || projectionHash == null || !projectionHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("retrieval projection manifest identity is invalid");
        }
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        lexicalProjections = List.copyOf(Objects.requireNonNull(lexicalProjections, "lexicalProjections"));
        var chunkIds = chunks.stream().map(RetrievalChunkProjection::chunkId).toList();
        var lexicalIds = lexicalProjections.stream().map(LexicalProjection::chunkId).toList();
        var searchableIds = chunks.stream()
                .filter(chunk -> chunk.indexMode()
                        != org.zipp.ai.domain.retrieval.model.valobj.RetrievalIndexMode.UNSEARCHABLE)
                .map(RetrievalChunkProjection::chunkId).collect(java.util.stream.Collectors.toSet());
        if (new HashSet<>(chunkIds).size() != chunkIds.size()
                || new HashSet<>(lexicalIds).size() != lexicalIds.size()
                || !searchableIds.equals(new HashSet<>(lexicalIds))) {
            throw new IllegalArgumentException("retrieval chunks and lexical projections are inconsistent");
        }
    }
}
