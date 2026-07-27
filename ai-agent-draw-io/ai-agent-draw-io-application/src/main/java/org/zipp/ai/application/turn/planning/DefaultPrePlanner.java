package org.zipp.ai.application.turn.planning;

import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.PlainResponseKind;
import org.zipp.ai.application.turn.PlainResponsePlan;
import org.zipp.ai.application.turn.TurnKey;
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
import org.zipp.ai.application.turn.demand.NeedsSourceClarification;
import org.zipp.ai.application.turn.demand.NoSourceDemand;
import org.zipp.ai.application.turn.demand.ResolvedSourceDemand;
import org.zipp.ai.application.turn.demand.SourceDemandResolution;
import org.zipp.ai.application.turn.demand.SourceDemandUnavailable;

import java.time.Duration;
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
            TurnKey turn,
            TurnClassification classification,
            String contextReadSetDigest,
            String inputBindingDigest
    ) {
        Objects.requireNonNull(turn, "turn");
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
            // Missing or ambiguous source input is a normal assistant response, not a failed turn.
            return new PrePlanOutcome.SourceFreeResponseReady(
                    new PlainResponsePlan(
                            PlainResponseKind.DIRECT_REPLY,
                            clarificationInstruction(
                                    clarification.kind(),
                                    classification.instruction().value())),
                    lineage,
                    contextReadSetDigest,
                    inputBindingDigest);
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
            if (usesDirect(accepted.kind())
                    && classification.intent().action()
                    != org.zipp.ai.application.turn.classification.SemanticAction.CREATE) {
                String code = classification.intent().action()
                        == org.zipp.ai.application.turn.classification.SemanticAction.EDIT
                        ? "UNSUPPORTED_DIRECT_EDIT" : "DIRECT_ACTION_UNSUPPORTED";
                return new PrePlanOutcome.Unsupported(
                        code, lineage, contextReadSetDigest, inputBindingDigest);
            }
            if (accepted.kind()
                    == org.zipp.ai.application.turn.demand.SourceDemandKind.OPTIONAL_DISCOVERY
                    && !supportsPlainFallback(classification)) {
                return new PrePlanOutcome.Unsupported(
                        "OPTIONAL_DISCOVERY_REQUIRES_PLAIN_DRAW",
                        lineage, contextReadSetDigest, inputBindingDigest);
            }
            return new PrePlanOutcome.SourcePlanningRequired(
                    turn, classification.instruction(), classification.intent(),
                    resolved, accepted, lineage,
                    contextReadSetDigest, inputBindingDigest);
        }
        return new PrePlanOutcome.SourceFreeResponseReady(
                new PlainResponsePlan(
                        PlainResponseKind.DIRECT_REPLY,
                        clarificationInstruction(
                                "AMBIGUOUS_SOURCE_DEMAND",
                                classification.instruction().value())),
                lineage,
                contextReadSetDigest,
                inputBindingDigest);
    }

    private String clarificationInstruction(String kind, String originalInstruction) {
        return switch (kind) {
            case "ATTACHMENT_REFERENT" ->
                    "Briefly ask the user to attach the image or PDF required by this request. "
                            + "Do not claim that a file was inspected. Original request: "
                            + originalInstruction;
            case "COMPOSITE_RETRIEVAL_SCOPE" ->
                    "Briefly ask the user which project or Chartbook documents must be used. "
                            + "Do not answer from general knowledge. Original request: "
                            + originalInstruction;
            default ->
                    "Briefly ask one concrete question that identifies the missing source. "
                            + "Do not claim that any source was inspected. Original request: "
                            + originalInstruction;
        };
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

    private boolean usesDirect(org.zipp.ai.application.turn.demand.SourceDemandKind kind) {
        return kind == org.zipp.ai.application.turn.demand.SourceDemandKind
                .CURRENT_MESSAGE_ATTACHMENTS_REQUIRED
                || kind == org.zipp.ai.application.turn.demand.SourceDemandKind
                .CURRENT_MESSAGE_DIRECT_REQUIRED
                || kind == org.zipp.ai.application.turn.demand.SourceDemandKind
                .CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL
                || kind == org.zipp.ai.application.turn.demand.SourceDemandKind
                .CURRENT_MESSAGE_DIRECT_RETRIEVAL_REQUIRED;
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
