package org.zipp.ai.domain.retrieval.projection;

import org.zipp.ai.domain.retrieval.model.valobj.ChunkEvidenceRole;

import java.util.Objects;

public record RetrievalEvidenceMapping(String evidenceId, ChunkEvidenceRole role, int ordinal,
                                       Integer charStart, Integer charEnd) {
    public RetrievalEvidenceMapping {
        if (evidenceId == null || evidenceId.isBlank() || ordinal < 0) {
            throw new IllegalArgumentException("retrieval evidence mapping identity is invalid");
        }
        role = Objects.requireNonNull(role, "role");
        if ((charStart == null) != (charEnd == null)
                || charStart != null && (charStart < 0 || charEnd <= charStart)) {
            throw new IllegalArgumentException("retrieval evidence character range is invalid");
        }
    }
}
