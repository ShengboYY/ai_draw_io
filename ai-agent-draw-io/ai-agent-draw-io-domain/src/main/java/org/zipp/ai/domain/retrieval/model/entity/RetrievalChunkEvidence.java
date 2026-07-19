package org.zipp.ai.domain.retrieval.model.entity;

import org.zipp.ai.domain.retrieval.model.valobj.ChunkEvidenceRole;

import java.util.Objects;

public record RetrievalChunkEvidence(EvidenceUnit evidence, ChunkEvidenceRole role, int ordinal) {
    public RetrievalChunkEvidence {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(role, "role");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal cannot be negative");
        }
    }
}
