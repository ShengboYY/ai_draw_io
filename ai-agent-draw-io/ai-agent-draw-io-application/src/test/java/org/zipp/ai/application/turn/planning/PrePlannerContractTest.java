package org.zipp.ai.application.turn.planning;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainResponseKind;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.classification.OutputIntent;
import org.zipp.ai.application.turn.classification.PlainDrawPlanFactory;
import org.zipp.ai.application.turn.classification.SemanticAction;
import org.zipp.ai.application.turn.classification.SemanticIntent;
import org.zipp.ai.application.turn.classification.TargetNeed;
import org.zipp.ai.application.turn.classification.TurnClassification;
import org.zipp.ai.application.turn.demand.AcceptedSourceDemand;
import org.zipp.ai.application.turn.demand.Confidence;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.demand.CurrentInstructionSpan;
import org.zipp.ai.application.turn.demand.DemandResolutionCode;
import org.zipp.ai.application.turn.demand.DemandResolutionReason;
import org.zipp.ai.application.turn.demand.NeedsSourceClarification;
import org.zipp.ai.application.turn.demand.NoSourceDemand;
import org.zipp.ai.application.turn.demand.ProposalEvidence;
import org.zipp.ai.application.turn.demand.ResolvedSourceDemand;
import org.zipp.ai.application.turn.demand.SourceDemandKind;
import org.zipp.ai.application.turn.demand.SourceDemandUnavailable;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PrePlannerContractTest {

    private static final String CONTEXT_DIGEST = "a".repeat(64);
    private static final String INPUT_DIGEST = "b".repeat(64);
    private static final TurnKey TURN = new TurnKey("owner-1", "conversation-1", "turn-1");

    @Test
    void noSourceCreateBecomesTheOnlyPlainRoute() {
        TurnClassification classification = classification(
                new SemanticIntent(SemanticAction.CREATE, OutputIntent.DRAWING,
                        TargetNeed.NOT_REQUIRED, "flowchart", "none"),
                new ResolvedSourceDemand(
                        new NoSourceDemand(),
                        List.of(new DemandResolutionReason(9, DemandResolutionCode.PLAIN_ONLY_ACTION))));

        PrePlanOutcome.SourceFreeReady ready = assertInstanceOf(
                PrePlanOutcome.SourceFreeReady.class,
                new DefaultPrePlanner(new PlainDrawPlanFactory()).plan(
                        TURN, classification, CONTEXT_DIGEST, INPUT_DIGEST));
        assertEquals(PlainDrawAction.CREATE, ready.plan().action());
        assertInstanceOf(TurnRouteDecision.Plain.class,
                new org.zipp.ai.application.turn.planning.DefaultTurnRouteDispatcher().dispatch(ready));
    }

    @Test
    void acceptedSourceDemandStaysTypedAndCannotBecomePlain() {
        AcceptedSourceDemand accepted = new AcceptedSourceDemand(
                SourceDemandKind.CURRENT_MESSAGE_ATTACHMENTS_REQUIRED, List.of("file-1"), null);
        TurnClassification classification = classification(
                new SemanticIntent(SemanticAction.CREATE, OutputIntent.DRAWING,
                        TargetNeed.NOT_REQUIRED, "flowchart", "none"),
                new ResolvedSourceDemand(
                        accepted,
                        List.of(new DemandResolutionReason(4, DemandResolutionCode.REQUIRED_PROPOSAL_ACCEPTED))));

        PrePlanOutcome.SourcePlanningRequired required = assertInstanceOf(
                PrePlanOutcome.SourcePlanningRequired.class,
                new DefaultPrePlanner(new PlainDrawPlanFactory()).plan(
                        TURN, classification, CONTEXT_DIGEST, INPUT_DIGEST));
        assertEquals(accepted, required.accepted());
        assertEquals("draw a flow", required.instruction().value());
        assertEquals(CONTEXT_DIGEST, required.contextReadSetDigest());
    }

    @Test
    void noSourceAnswerBecomesTheSourceFreeResponseRoute() {
        TurnClassification classification = classification(
                new SemanticIntent(SemanticAction.ANSWER, OutputIntent.TEXT,
                        TargetNeed.NOT_REQUIRED, "unknown", "none"),
                new ResolvedSourceDemand(new NoSourceDemand(), List.of()));

        PrePlanOutcome.SourceFreeResponseReady ready = assertInstanceOf(
                PrePlanOutcome.SourceFreeResponseReady.class,
                new DefaultPrePlanner(new PlainDrawPlanFactory()).plan(
                        TURN, classification, CONTEXT_DIGEST, INPUT_DIGEST));
        assertEquals(PlainResponseKind.ANSWER, ready.plan().kind());
        assertEquals(false, ready.plan().includeCanvasContext());
        assertInstanceOf(TurnRouteDecision.Response.class,
                new DefaultTurnRouteDispatcher().dispatch(ready));
    }

    @Test
    void questionAboutTheCurrentDiagramReadsCanvasWithoutMutatingIt() {
        TurnClassification classification = classification(
                new SemanticIntent(SemanticAction.ANSWER, OutputIntent.TEXT,
                        TargetNeed.CANVAS_REQUIRED, "flowchart", "none"),
                new ResolvedSourceDemand(new NoSourceDemand(), List.of()));

        PrePlanOutcome.SourceFreeResponseReady ready = assertInstanceOf(
                PrePlanOutcome.SourceFreeResponseReady.class,
                new DefaultPrePlanner(new PlainDrawPlanFactory()).plan(
                        TURN, classification, CONTEXT_DIGEST, INPUT_DIGEST));

        assertEquals(PlainResponseKind.ANSWER, ready.plan().kind());
        assertEquals(true, ready.plan().includeCanvasContext());
    }

    @Test
    void clarificationBecomesANormalResponseWhileUnavailableStillStops() {
        TurnClassification clarification = classification(
                new SemanticIntent(SemanticAction.CREATE, OutputIntent.DRAWING,
                        TargetNeed.NOT_REQUIRED, "flowchart", "none"),
                new NeedsSourceClarification("AMBIGUOUS_SOURCE_DEMAND", List.of()));
        TurnClassification unavailable = classification(
                new SemanticIntent(SemanticAction.CREATE, OutputIntent.DRAWING,
                        TargetNeed.NOT_REQUIRED, "flowchart", "none"),
                new SourceDemandUnavailable("DEMAND_MODEL_DOWN", Duration.ofSeconds(1)));

        PrePlanOutcome.SourceFreeResponseReady response = assertInstanceOf(
                PrePlanOutcome.SourceFreeResponseReady.class,
                new DefaultPrePlanner(new PlainDrawPlanFactory()).plan(
                        TURN, clarification, CONTEXT_DIGEST, INPUT_DIGEST));
        assertEquals(PlainResponseKind.DIRECT_REPLY, response.plan().kind());
        assertInstanceOf(PrePlanOutcome.Unavailable.class,
                new DefaultPrePlanner(new PlainDrawPlanFactory()).plan(
                        TURN, unavailable, CONTEXT_DIGEST, INPUT_DIGEST));
    }

    @Test
    void unsupportedNoSourceActionIsNotSilentlyDowngraded() {
        TurnClassification classification = classification(
                new SemanticIntent(SemanticAction.ANSWER, OutputIntent.REVIEW,
                        TargetNeed.NOT_REQUIRED, "unknown", "none"),
                new ResolvedSourceDemand(new NoSourceDemand(), List.of()));

        PrePlanOutcome.Unsupported unsupported = assertInstanceOf(
                PrePlanOutcome.Unsupported.class,
                new DefaultPrePlanner(new PlainDrawPlanFactory()).plan(
                        TURN, classification, CONTEXT_DIGEST, INPUT_DIGEST));
        assertEquals("PLAIN_RESPONSE_ACTION_UNSUPPORTED", unsupported.code());
    }

    @Test
    void optionalDiscoveryWithoutAPlainDrawBranchStopsBeforeProbe() {
        AcceptedSourceDemand accepted = new AcceptedSourceDemand(
                SourceDemandKind.OPTIONAL_DISCOVERY, List.of(), "project facts");
        TurnClassification classification = classification(
                new SemanticIntent(SemanticAction.ANSWER, OutputIntent.TEXT,
                        TargetNeed.NOT_REQUIRED, "unknown", "none"),
                new ResolvedSourceDemand(accepted, List.of()));

        PrePlanOutcome.Unsupported unsupported = assertInstanceOf(
                PrePlanOutcome.Unsupported.class,
                new DefaultPrePlanner(new PlainDrawPlanFactory()).plan(
                        TURN, classification, CONTEXT_DIGEST, INPUT_DIGEST));

        assertEquals("OPTIONAL_DISCOVERY_REQUIRES_PLAIN_DRAW", unsupported.code());
    }

    @Test
    void editWithDirectStopsBeforeProbeInsteadOfBecomingPlainOrRetrieval() {
        AcceptedSourceDemand accepted = new AcceptedSourceDemand(
                SourceDemandKind.CURRENT_MESSAGE_DIRECT_REQUIRED,
                List.of("file-1"), null);
        TurnClassification classification = classification(
                new SemanticIntent(SemanticAction.EDIT, OutputIntent.DRAWING,
                        TargetNeed.CANVAS_REQUIRED, "flowchart", "none"),
                new ResolvedSourceDemand(accepted, List.of()));

        PrePlanOutcome.Unsupported unsupported = assertInstanceOf(
                PrePlanOutcome.Unsupported.class,
                new DefaultPrePlanner(new PlainDrawPlanFactory()).plan(
                        TURN, classification, CONTEXT_DIGEST, INPUT_DIGEST));

        assertEquals("UNSUPPORTED_DIRECT_EDIT", unsupported.code());
    }

    private TurnClassification classification(
            SemanticIntent intent,
            org.zipp.ai.application.turn.demand.SourceDemandResolution resolution
    ) {
        CurrentInstruction instruction = new CurrentInstruction("draw a flow");
        return new TurnClassification(
                instruction,
                intent,
                new org.zipp.ai.application.turn.demand.NoSourceDemandProposal(
                        new ProposalEvidence(
                                List.of(new CurrentInstructionSpan(0, instruction.value().length(),
                                        instruction.spanDigest(0, instruction.value().length()))),
                                Confidence.HIGH,
                                java.util.Optional.empty(), "c".repeat(64),
                                "m2-demand-model", "m2-demand-policy"),
                        "test"),
                resolution);
    }
}
