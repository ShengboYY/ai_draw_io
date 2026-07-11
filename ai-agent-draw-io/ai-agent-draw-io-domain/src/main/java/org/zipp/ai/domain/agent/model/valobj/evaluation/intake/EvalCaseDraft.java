package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/** Synthetic draft suggestion; it must never contain production identifiers or raw payloads. */
@Data
@Builder
public class EvalCaseDraft {
    private String id;
    private String candidateId;
    private String failureSummary;
    private String suspectedFailureFamily;
    private List<String> userTurns;
    private String initialFixtureHint;
    private String expectedRoute;
    private List<String> suggestedAssertions;
    private String confidence;
    private boolean needsHumanReview;
    private String sanitizerVersion;
    private String modelVersion;
    private Instant createdAt;
}
