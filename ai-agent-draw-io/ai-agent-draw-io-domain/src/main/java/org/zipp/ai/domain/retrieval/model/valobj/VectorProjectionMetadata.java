package org.zipp.ai.domain.retrieval.model.valobj;

import org.zipp.ai.domain.ingestion.model.valobj.EvidenceModality;

import java.util.Objects;

/** Authoritative MySQL metadata allowed to become opaque Pinecone metadata. */
public record VectorProjectionMetadata(String chunkId, String vectorId, String projectionFingerprint,
                                       RetrievalChunkType chunkType, EvidenceModality modality,
                                       Integer pageNo, String language) {
    public VectorProjectionMetadata {
        if (chunkId == null || chunkId.isBlank() || vectorId == null || vectorId.isBlank()
                || projectionFingerprint == null || !projectionFingerprint.matches("[0-9a-f]{64}")
                || pageNo != null && pageNo < 1 || language == null || language.isBlank()) {
            throw new IllegalArgumentException("vector projection metadata is invalid");
        }
        chunkType = Objects.requireNonNull(chunkType, "chunkType");
        modality = Objects.requireNonNull(modality, "modality");
    }
}
