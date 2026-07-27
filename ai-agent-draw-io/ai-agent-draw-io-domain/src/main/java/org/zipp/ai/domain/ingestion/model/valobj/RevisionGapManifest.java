package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Exact, user-explainable coverage gaps for a PARTIAL_READY revision. */
public record RevisionGapManifest(String schemaVersion, String revisionId, String versionId,
                                  List<Gap> gaps) {
    public RevisionGapManifest {
        if (schemaVersion == null || schemaVersion.isBlank()
                || revisionId == null || revisionId.isBlank()
                || versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("gap manifest identity is invalid");
        }
        gaps = List.copyOf(Objects.requireNonNull(gaps, "gaps"));
        if (gaps.isEmpty() || new HashSet<>(gaps.stream()
                .map(gap -> gap.pageNo() + ":" + gap.modality()).toList()).size() != gaps.size()) {
            throw new IllegalArgumentException("gap manifest requires unique page modalities");
        }
    }

    public record Gap(int pageNo, EvidenceModality modality, String errorCode,
                      boolean retryable, double coverageImpact) {
        public Gap {
            if (pageNo < 1 || modality == null
                    || errorCode == null || !errorCode.matches("[A-Z0-9_]{3,64}")
                    || coverageImpact <= 0 || coverageImpact > 1) {
                throw new IllegalArgumentException("gap entry is invalid");
            }
        }
    }
}
