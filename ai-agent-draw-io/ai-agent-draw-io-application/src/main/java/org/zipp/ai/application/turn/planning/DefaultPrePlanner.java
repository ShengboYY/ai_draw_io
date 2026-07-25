package org.zipp.ai.application.turn.planning;

import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.classification.PlainDrawPlanDecision;
import org.zipp.ai.application.turn.classification.PlainDrawPlanFactory;
import org.zipp.ai.application.turn.classification.PlainDrawPlanReady;
import org.zipp.ai.application.turn.classification.PlainDrawPlanRejected;
import org.zipp.ai.application.turn.classification.TurnClassification;
import org.zipp.ai.application.turn.demand.AcceptedSourceDemand;
import org.zipp.ai.application.turn.demand.AmbiguousSourceDemand;
import org.zipp.ai.application.turn.demand.DemandResolutionReason;
import org.zipp.ai.application.turn.demand.NeedsSourceClarification;
import org.zipp.ai.application.turn.demand.NoSourceDemand;
import org.zipp.ai.application.turn.demand.ResolvedSourceDemand;
import org.zipp.ai.application.turn.demand.SourceDemandResolution;
import org.zipp.ai.application.turn.demand.SourceDemandUnavailable;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic boundary before Probe: it either returns a closed Plain plan or preserves a
 * typed source-planning requirement. It never reads availability or executes source I/O.
 */
public final class DefaultPrePlanner {

    private final PlainDrawPlanFactory plainPlans;

    public DefaultPrePlanner(PlainDrawPlanFactory plainPlans) {
        this.plainPlans = Objects.requireNonNull(plainPlans, "plainPlans");
    }

    public PrePlanOutcome plan(
            TurnClassification classification,
            String contextReadSetDigest,
            String inputBindingDigest
    ) {
        Objects.requireNonNull(classification, "classification");
        PlanningLineageFingerprint lineage = PlanningLineageFingerprintCalculator.calculate(
                classification, contextReadSetDigest, inputBindingDigest);
        SourceDemandResolution resolution = classification.demandResolution();
        if (resolution instanceof SourceDemandUnavailable unavailable) {
            return new PrePlanOutcome.Unavailable(
                    unavailable.code(), unavailable.retryAfter(), lineage,
                    contextReadSetDigest, inputBindingDigest);
        }
        if (resolution instanceof NeedsSourceClarification clarification) {
            return new PrePlanOutcome.NeedsClarification(
                    clarification, lineage, contextReadSetDigest, inputBindingDigest);
        }
        if (!(resolution instanceof ResolvedSourceDemand resolved)) {
            return unavailable("SOURCE_DEMAND_RESOLUTION_UNAVAILABLE", lineage,
                    contextReadSetDigest, inputBindingDigest);
        }

        if (resolved.decision() instanceof NoSourceDemand) {
            PlainDrawPlanDecision decision = plainPlans.create(classification);
            if (decision instanceof PlainDrawPlanReady ready) {
                return new PrePlanOutcome.SourceFreeReady(
                        ready.plan(), lineage, contextReadSetDigest, inputBindingDigest);
            }
            return new PrePlanOutcome.Unsupported(
                    ((PlainDrawPlanRejected) decision).code(), lineage,
                    contextReadSetDigest, inputBindingDigest);
        }
        if (resolved.decision() instanceof AcceptedSourceDemand accepted) {
            return new PrePlanOutcome.SourcePlanningRequired(
                    classification.intent(), resolved, accepted, lineage,
                    contextReadSetDigest, inputBindingDigest);
        }
        AmbiguousSourceDemand ambiguous = (AmbiguousSourceDemand) resolved.decision();
        return new PrePlanOutcome.NeedsClarification(
                new NeedsSourceClarification(
                        "AMBIGUOUS_SOURCE_DEMAND",
                        List.of(new DemandResolutionReason(
                                4, org.zipp.ai.application.turn.demand.DemandResolutionCode.CLARIFICATION_REQUIRED))),
                lineage, contextReadSetDigest, inputBindingDigest);
    }

    private PrePlanOutcome.Unavailable unavailable(
            String code,
            PlanningLineageFingerprint lineage,
            String contextReadSetDigest,
            String inputBindingDigest
    ) {
        return new PrePlanOutcome.Unavailable(
                code, Duration.ZERO, lineage, contextReadSetDigest, inputBindingDigest);
    }
}
