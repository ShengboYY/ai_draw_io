package org.zipp.ai.domain.retrieval.projection;

import org.zipp.ai.domain.ingestion.model.valobj.EvidenceModality;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalChunkType;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalIndexMode;

import java.util.List;
import java.util.Objects;

/** Immutable retrieval representation; citation text still comes only from mapped Evidence. */
public record RetrievalChunkProjection(String chunkId, String pageId, String sectionId,
                                       RetrievalChunkType chunkType, EvidenceModality modality,
                                       String languagePrimary, boolean citable,
                                       RetrievalIndexMode indexMode, String retrievalText,
                                       String retrievalTextSha256, String parentContext,
                                       List<String> parentEvidenceIds,
                                       int tokenCount, double quality, int structuralOrdinal,
                                       List<RetrievalEvidenceMapping> evidenceMappings) {
    public RetrievalChunkProjection {
        if (chunkId == null || chunkId.isBlank() || languagePrimary == null || languagePrimary.isBlank()
                || retrievalText == null || retrievalText.isBlank()
                || retrievalTextSha256 == null || !retrievalTextSha256.matches("[0-9a-f]{64}")
                || tokenCount < 1 || quality < 0 || quality > 1 || structuralOrdinal < 1) {
            throw new IllegalArgumentException("retrieval chunk projection identity is invalid");
        }
        pageId = blankToNull(pageId);
        sectionId = blankToNull(sectionId);
        chunkType = Objects.requireNonNull(chunkType, "chunkType");
        modality = Objects.requireNonNull(modality, "modality");
        indexMode = Objects.requireNonNull(indexMode, "indexMode");
        parentContext = blankToNull(parentContext);
        parentEvidenceIds = List.copyOf(Objects.requireNonNull(parentEvidenceIds, "parentEvidenceIds"));
        evidenceMappings = List.copyOf(Objects.requireNonNull(evidenceMappings, "evidenceMappings"));
        if ((parentContext == null) != parentEvidenceIds.isEmpty()) {
            throw new IllegalArgumentException("parent context must retain its Evidence identities");
        }
        if (citable && (!chunkType.allowsCitation() || evidenceMappings.stream()
                .noneMatch(mapping -> mapping.role()
                        == org.zipp.ai.domain.retrieval.model.valobj.ChunkEvidenceRole.PRIMARY))) {
            throw new IllegalArgumentException("citable retrieval chunks require primary Evidence");
        }
        if (!citable && chunkType.allowsCitation()) {
            throw new IllegalArgumentException("leaf retrieval chunks must remain citable");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
