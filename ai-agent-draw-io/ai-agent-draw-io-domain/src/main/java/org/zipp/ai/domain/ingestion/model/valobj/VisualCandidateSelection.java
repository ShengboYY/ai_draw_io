package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.List;
import java.util.Objects;

/** Bounded, auditable result of local visual-candidate selection. */
public record VisualCandidateSelection(List<VisualCandidate> selectedCandidates,
                                       int selectedPageCount,
                                       int totalCandidateCount,
                                       int skippedCandidateCount) {
    public VisualCandidateSelection {
        selectedCandidates = List.copyOf(Objects.requireNonNull(selectedCandidates, "selectedCandidates"));
        if (selectedPageCount < 0 || totalCandidateCount < selectedCandidates.size()
                || skippedCandidateCount != totalCandidateCount - selectedCandidates.size()) {
            throw new IllegalArgumentException("visual candidate selection counts are inconsistent");
        }
    }
}
