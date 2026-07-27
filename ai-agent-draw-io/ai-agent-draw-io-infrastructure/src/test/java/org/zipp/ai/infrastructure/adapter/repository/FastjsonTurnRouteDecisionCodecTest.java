package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.PlainResponseKind;
import org.zipp.ai.application.turn.PlainResponsePlan;
import org.zipp.ai.application.turn.checkpoint.EncodedTurnRouteDecision;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpoint;
import org.zipp.ai.application.turn.classification.OutputIntent;
import org.zipp.ai.application.turn.classification.SemanticAction;
import org.zipp.ai.application.turn.classification.SemanticIntent;
import org.zipp.ai.application.turn.classification.TargetNeed;
import org.zipp.ai.application.turn.demand.AcceptedSourceDemand;
import org.zipp.ai.application.turn.demand.DemandResolutionCode;
import org.zipp.ai.application.turn.demand.DemandResolutionReason;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.demand.ResolvedSourceDemand;
import org.zipp.ai.application.turn.demand.SourceDemandKind;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.application.turn.planning.PrePlanOutcome;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FastjsonTurnRouteDecisionCodecTest {

    private static final String CONTEXT_DIGEST = "a".repeat(64);
    private static final String INPUT_DIGEST = "b".repeat(64);

    private final FastjsonTurnRouteDecisionCodec codec = new FastjsonTurnRouteDecisionCodec();

    @Test
    void roundTripsPlainRouteWithoutAddingSourceFacts() {
        TurnRouteDecision original = new TurnRouteDecision.Plain(
                new PrePlanOutcome.SourceFreeReady(
                        new PlainDrawPlan(PlainDrawAction.CREATE, "draw a flow"),
                        new PlanningLineageFingerprint("c".repeat(64)),
                        CONTEXT_DIGEST,
                        INPUT_DIGEST));

        assertEquals(original, decode(original));
    }

    @Test
    void roundTripsSourceFreeResponseRoute() {
        TurnRouteDecision original = new TurnRouteDecision.Response(
                new PrePlanOutcome.SourceFreeResponseReady(
                        new PlainResponsePlan(
                                PlainResponseKind.REVIEW,
                                "review the diagram",
                                true),
                        new PlanningLineageFingerprint("c".repeat(64)),
                        CONTEXT_DIGEST,
                        INPUT_DIGEST));

        assertEquals(original, decode(original));
    }

    @Test
    void roundTripsTypedSourcePlanningRouteAndReasons() {
        AcceptedSourceDemand accepted = new AcceptedSourceDemand(
                SourceDemandKind.CURRENT_MESSAGE_ATTACHMENTS_REQUIRED,
                List.of("file-1"),
                "architecture");
        TurnRouteDecision original = new TurnRouteDecision.SourcePlanning(
                new PrePlanOutcome.SourcePlanningRequired(
                        new org.zipp.ai.application.turn.TurnKey(
                                "owner-1", "conversation-1", "turn-1"),
                        new CurrentInstruction("draw from the architecture"),
                        new SemanticIntent(
                                SemanticAction.EDIT,
                                OutputIntent.DRAWING,
                                TargetNeed.CANVAS_REQUIRED,
                                "flowchart",
                                "none"),
                        new ResolvedSourceDemand(accepted, List.of(
                                new DemandResolutionReason(
                                        4, DemandResolutionCode.REQUIRED_PROPOSAL_ACCEPTED))),
                        accepted,
                        new PlanningLineageFingerprint("c".repeat(64)),
                        CONTEXT_DIGEST,
                        INPUT_DIGEST));

        assertEquals(original, decode(original));
    }

    @Test
    void rejectsUnknownRouteKind() {
        TurnDecisionCheckpoint checkpoint = TurnDecisionCheckpoint.create(
                1, CONTEXT_DIGEST, INPUT_DIGEST, "UNKNOWN", "{}");

        assertThrows(IllegalArgumentException.class, () -> codec.decode(checkpoint));
    }

    private TurnRouteDecision decode(TurnRouteDecision original) {
        EncodedTurnRouteDecision encoded = codec.encode(original);
        return codec.decode(TurnDecisionCheckpoint.create(
                1, CONTEXT_DIGEST, INPUT_DIGEST, encoded.kind(), encoded.json()));
    }
}
