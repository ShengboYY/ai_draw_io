package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.List;
import java.util.Objects;

/** Immutable citable evidence boundaries; generated visual descriptions are intentionally absent. */
public record EvidenceManifest(String schemaVersion, String revisionId, String versionId,
                               String structureHash, String builderFingerprint, String evidenceHash,
                               List<EvidenceUnit> units, List<EvidenceRelation> relations,
                               List<SectionHeadingEvidence> sectionHeadings) {
    public EvidenceManifest {
        if (schemaVersion == null || schemaVersion.isBlank() || revisionId == null || revisionId.isBlank()
                || versionId == null || versionId.isBlank()
                || structureHash == null || !structureHash.matches("[0-9a-f]{64}")
                || builderFingerprint == null || builderFingerprint.isBlank()
                || evidenceHash == null || !evidenceHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("evidence manifest identity is invalid");
        }
        units = List.copyOf(Objects.requireNonNull(units, "units"));
        relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
        sectionHeadings = List.copyOf(Objects.requireNonNull(sectionHeadings, "sectionHeadings"));
    }
}
