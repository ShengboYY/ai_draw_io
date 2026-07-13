package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.*;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;
import org.zipp.ai.domain.agent.service.evaluation.intake.TraceFindingViewService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class TraceFindingViewServiceTest {

    @Test
    public void projectionKeepsAnalyzerJudgmentSeparateFromTraceEvidenceAndReview() {
        CandidateStore candidates = new CandidateStore();
        TraceFindingViewService service = new TraceFindingViewService(candidates);

        TraceFindingView view = service.list(null, null, "VLM", "edit_existing", "drawing-agent",
                "run-1", null, null, 1000L, 5000L, 20, 0).get(0);

        assertEquals("VLM", view.analyzerType());
        assertEquals("edit_existing", view.routeType());
        assertEquals(Long.valueOf(3400L), view.sourceLatencyMs());
        assertEquals(List.of("Visual issue code: LOW_CONTRAST"), view.analysisEvidence());
        assertEquals(List.of("trace://run/run-1"), view.evidenceRefs());
        assertEquals("reviewer-1", view.reviewedBy());
        assertEquals("UI", view.recommendation().suspectedLayer());
        assertEquals("VLM", candidates.filter.analyzerType());
    }

    private static final class CandidateStore implements ITraceToEvalStore {
        private TraceFindingFilter filter;
        private final EvalCaseCandidate candidate = EvalCaseCandidate.builder().id("ecc-1").sourceRunId("run-1")
                .sourceAgentId("drawing-agent").sourcePhase("visual_analysis").failureFamily("visual_low_contrast")
                .ruleId("visual_miner").evidenceSummary("Low contrast").risk("medium")
                .discoveredAt(Instant.parse("2026-07-13T00:00:00Z")).policyVersion("visual-v1")
                .status(EvalCandidateStatus.TRIAGED).createdBy("visual-miner").detectionSource("MODEL_DETECTED")
                .modelVersion("vlm-v1").modelConfidence(0.8D)
                .modelEvidence(List.of("Visual issue code: LOW_CONTRAST")).build();
        @Override public Optional<EvalCaseCandidate> findCandidate(String id) { return Optional.of(candidate); }
        @Override public Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String run, String family) { return Optional.empty(); }
        @Override public List<TraceFindingCandidate> listFindingCandidates(TraceFindingFilter value) {
            filter = value;
            return List.of(new TraceFindingCandidate(candidate,
                    new TraceFindingContext("edit_existing", "drawing-agent", 3400L,
                            "reviewer-1", Instant.parse("2026-07-13T00:01:00Z"))));
        }
        @Override public void insertCandidate(EvalCaseCandidate value) { }
        @Override public void updateCandidateStatus(String id, EvalCandidateStatus status) { }
        @Override public void insertReview(EvalCaseReview review) { }
        @Override public void insertLineage(EvalCaseLineage lineage) { }
    }
}
