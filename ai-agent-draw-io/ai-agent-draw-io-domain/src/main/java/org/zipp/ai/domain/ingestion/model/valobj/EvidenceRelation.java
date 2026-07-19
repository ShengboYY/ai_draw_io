package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.Objects;

public record EvidenceRelation(String fromEvidenceId, String toEvidenceId,
                               EvidenceRelationType relationType, double weight) {
    public EvidenceRelation {
        if (fromEvidenceId == null || fromEvidenceId.isBlank()
                || toEvidenceId == null || toEvidenceId.isBlank()
                || fromEvidenceId.equals(toEvidenceId) || weight <= 0 || weight > 1) {
            throw new IllegalArgumentException("evidence relation identity is invalid");
        }
        relationType = Objects.requireNonNull(relationType, "relationType");
    }
}
