package org.zipp.ai.domain.retrieval.projection;

import org.zipp.ai.domain.ingestion.model.valobj.EvidenceModality;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalChunkType;

import java.util.Objects;

/** One logical chunk projection; the text is transient Worker input and never Pinecone metadata. */
public record VectorProjectionTarget(String chunkId, String vectorId, String retrievalText,
                                     String retrievalTextSha256, RetrievalChunkType chunkType,
                                     EvidenceModality modality, String pageId, String language,
                                     String projectionFingerprint) {
    public VectorProjectionTarget {
        chunkId = requireText(chunkId, "chunkId");
        vectorId = requireText(vectorId, "vectorId");
        retrievalText = requireText(retrievalText, "retrievalText");
        if (retrievalTextSha256 == null || !retrievalTextSha256.matches("[0-9a-f]{64}")
                || projectionFingerprint == null || !projectionFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("vector projection fingerprints are invalid");
        }
        chunkType = Objects.requireNonNull(chunkType, "chunkType");
        modality = Objects.requireNonNull(modality, "modality");
        pageId = blankToNull(pageId);
        language = requireText(language, "language");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
