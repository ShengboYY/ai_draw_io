package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

import java.time.Instant;
import java.util.List;

/** Read-only Trace Analysis projection assembled from Candidate, evidence and review records. */
public record TraceFindingView(
        String candidateId,
        String sourceRunId,
        String analyzerType,
        String analyzerVersion,
        String failureFamily,
        String risk,
        Double confidence,
        String summary,
        List<String> evidenceRefs,
        String recommendation,
        EvalCandidateStatus candidateStatus,
        Instant discoveredAt,
        String reviewedBy,
        Instant reviewedAt) {

    public TraceFindingView {
        // Copy evidence so the view cannot become a second mutable Finding model.
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }

    /** The UI group is always derived from Candidate state and cannot diverge from the write model. */
    public TraceFindingStatusGroup statusGroup() {
        return TraceFindingStatusGroup.from(candidateStatus);
    }
}
