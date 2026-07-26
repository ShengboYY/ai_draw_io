package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.planning.OptionalEvidenceOutcome;
import org.zipp.ai.application.turn.planning.OptionalRetrievalDrawPlan;
import org.zipp.ai.application.turn.planning.SourcePlanDecision;

import java.time.Instant;
import java.util.Objects;

/**
 * Enters only planner-signed Plain branches. The Plain handler starts a fresh source-free model
 * invocation from the pinned context after grounded branch state has been destroyed.
 */
public final class OptionalEnrichmentFallbackHandler {

    private final PlainDrawingHandler plainDrawing;

    public OptionalEnrichmentFallbackHandler(PlainDrawingHandler plainDrawing) {
        this.plainDrawing = Objects.requireNonNull(plainDrawing, "plainDrawing");
    }

    public FencedCommitOutcome executeProbeFallback(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            SourcePlanDecision.ProbeFallbackReady fallback,
            TurnEventSink events
    ) {
        Objects.requireNonNull(fallback, "fallback");
        publishSkipped(events, fallback.reason().name(), fallback.fallback().branchId());
        return plainDrawing.execute(
                attempt, context, readSet, fallback.fallback().plan(), events);
    }

    public FencedCommitOutcome executeEvidenceFallback(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            OptionalRetrievalDrawPlan plan,
            OptionalEvidenceOutcome.FallbackEligible fallback,
            OptionalPrimaryBranchScope primaryScope,
            TurnEventSink events
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(fallback, "fallback");
        Objects.requireNonNull(primaryScope, "primaryScope");
        // Destroy all grounded branch capabilities before creating the source-free invocation.
        try {
            primaryScope.discard();
        } finally {
            primaryScope.close();
        }
        publishSkipped(events, fallback.reason().name(), plan.validatedFallback().branchId());
        return plainDrawing.execute(
                attempt, context, readSet, plan.validatedFallback().plan(), events);
    }

    private void publishSkipped(TurnEventSink events, String reason, String branchId) {
        Objects.requireNonNull(events, "events").publish(new TurnEvent(
                "enrichment_skipped",
                "reason=" + reason + ",branch=" + branchId,
                Instant.now()));
    }
}
