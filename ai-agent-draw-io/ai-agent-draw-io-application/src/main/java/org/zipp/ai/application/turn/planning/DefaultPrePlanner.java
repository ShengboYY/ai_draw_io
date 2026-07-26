package org.zipp.ai.application.turn.planning;

import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.classification.PlainDrawPlanDecision;
import org.zipp.ai.application.turn.classification.PlainDrawPlanFactory;
import org.zipp.ai.application.turn.classification.PlainDrawPlanReady;
import org.zipp.ai.application.turn.classification.PlainDrawPlanRejected;
import org.zipp.ai.application.turn.classification.PlainResponsePlanDecision;
import org.zipp.ai.application.turn.classification.PlainResponsePlanFactory;
import org.zipp.ai.application.turn.classification.PlainResponsePlanReady;
import org.zipp.ai.application.turn.classification.PlainResponsePlanRejected;
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
 * Deterministic boundary before Probe: it returns a closed source-free draw/response plan or
 * preserves a typed source-planning requirement. It never reads availability or executes source I/O.
 */
public final class DefaultPrePlanner {

    private final PlainDrawPlanFactory plainPlans;
    private final PlainResponsePlanFactory responsePlans;

    public DefaultPrePlanner(PlainDrawPlanFactory plainPlans) {
        this(plainPlans, new PlainResponsePlanFactory());
    }

    public DefaultPrePlanner(
            PlainDrawPlanFactory plainPlans,
            PlainResponsePlanFactory responsePlans
    ) {
        this.plainPlans = Objects.requireNonNull(plainPlans, "plainPlans");
        this.responsePlans = Objects.requireNonNull(responsePlans, "responsePlans");
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
            PlainResponsePlanDecision response = responsePlans.create(classification);
            if (response instanceof PlainResponsePlanReady ready) {
                return new PrePlanOutcome.SourceFreeResponseReady(
                        ready.plan(), lineage, contextReadSetDigest, inputBindingDigest);
            }
            if (isResponseAction(classification)) {
                return new PrePlanOutcome.Unsupported(
                        ((PlainResponsePlanRejected) response).code(), lineage,
                        contextReadSetDigest, inputBindingDigest);
            }
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
            if (accepted.kind()
                    == org.zipp.ai.application.turn.demand.SourceDemandKind.OPTIONAL_DISCOVERY
                    && !supportsPlainFallback(classification)) {
                return new PrePlanOutcome.Unsupported(
                        "OPTIONAL_DISCOVERY_REQUIRES_PLAIN_DRAW",
                        lineage, contextReadSetDigest, inputBindingDigest);
            }
            return new PrePlanOutcome.SourcePlanningRequired(
                    classification.instruction(), classification.intent(), resolved, accepted, lineage,
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

    private boolean isResponseAction(TurnClassification classification) {
        return switch (classification.intent().action()) {
            case ANSWER, REVIEW, DIRECT_REPLY -> true;
            default -> false;
        };
    }

    private boolean supportsPlainFallback(TurnClassification classification) {
        return switch (classification.intent().action()) {
            case CREATE, EDIT, LAYOUT -> true;
            default -> false;
        };
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
