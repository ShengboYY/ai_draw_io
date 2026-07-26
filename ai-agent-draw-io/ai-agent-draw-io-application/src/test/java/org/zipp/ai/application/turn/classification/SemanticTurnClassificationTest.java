package org.zipp.ai.application.turn.classification;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.demand.Confidence;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.demand.CurrentInstructionSpan;
import org.zipp.ai.application.turn.demand.DemandResolutionPolicy;
import org.zipp.ai.application.turn.demand.NoSourceDemandProposal;
import org.zipp.ai.application.turn.demand.ProposalEvidence;
import org.zipp.ai.application.turn.demand.RestrictedSourceDemandInput;
import org.zipp.ai.application.turn.demand.SourceDemandInterpreterPort;
import org.zipp.ai.application.turn.demand.SourceDemandProposalOutcome;
import org.zipp.ai.application.turn.demand.SourceDemandProposalReady;
import org.zipp.ai.application.turn.demand.SourceDemandResolver;
import org.zipp.ai.application.turn.demand.SourceDemandKind;
import org.zipp.ai.application.turn.demand.TypedSourceDemandProposal;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SemanticTurnClassificationTest {

    @Test
    void plainClassificationProducesPlainPlanOnlyAfterDemandResolution() {
        CurrentInstruction instruction = new CurrentInstruction("draw a login flow");
        RestrictedSourceDemandInput demandInput = input(instruction, Optional.empty());
        SemanticIntent intent = new SemanticIntent(
                SemanticAction.CREATE, OutputIntent.DRAWING, TargetNeed.NOT_REQUIRED, "flowchart", "none");
        TurnClassificationOutcome outcome = service(
                new SemanticIntentReady(intent),
                new SourceDemandProposalReady(noSource(demandInput)))
                .classify(routerInput(instruction), demandInput);

        TurnClassificationReady ready = assertInstanceOf(TurnClassificationReady.class, outcome);
        PlainDrawPlanReady plan = assertInstanceOf(
                PlainDrawPlanReady.class, new PlainDrawPlanFactory().create(ready.classification()));
        assertEquals(org.zipp.ai.application.turn.PlainDrawAction.CREATE, plan.plan().action());
        assertEquals("draw a login flow", plan.plan().instruction());
    }

    @Test
    void acceptedSourceDemandCannotEnterPlainPlan() {
        CurrentInstruction instruction = new CurrentInstruction("use the attached specification");
        RestrictedSourceDemandInput demandInput = input(
                instruction, Optional.of("chartbook-1"), new OpaqueConversationFileRef("file-1"));
        TypedSourceDemandProposal proposal = new TypedSourceDemandProposal(
                SourceDemandKind.CURRENT_MESSAGE_ATTACHMENTS_REQUIRED,
                List.of("file-1"), null, evidence(demandInput), "use attachment");
        TurnClassificationReady ready = assertInstanceOf(TurnClassificationReady.class,
                service(
                        new SemanticIntentReady(new SemanticIntent(
                                SemanticAction.EDIT, OutputIntent.DRAWING,
                                TargetNeed.CANVAS_REQUIRED, "flowchart", "none")),
                        new SourceDemandProposalReady(proposal))
                        .classify(routerInput(instruction), demandInput));

        PlainDrawPlanRejected rejected = assertInstanceOf(
                PlainDrawPlanRejected.class, new PlainDrawPlanFactory().create(ready.classification()));
        assertEquals("SOURCE_PLANNING_REQUIRED", rejected.code());
    }

    @Test
    void routerUnavailableStopsBeforeDemandInterpreter() {
        CurrentInstruction instruction = new CurrentInstruction("draw");
        int[] interpreterCalls = {0};
        SourceDemandInterpreterPort interpreter = input -> {
            interpreterCalls[0]++;
            return new SourceDemandProposalReady(noSource(input));
        };
        TurnClassificationService service = new TurnClassificationService(
                input -> new SemanticIntentUnavailable("ROUTER_UNAVAILABLE"),
                interpreter,
                new SourceDemandResolver(),
                DemandResolutionPolicy.m2Default());

        TurnClassificationUnavailable unavailable = assertInstanceOf(
                TurnClassificationUnavailable.class,
                service.classify(routerInput(instruction), input(instruction, Optional.empty())));

        assertEquals("ROUTER_UNAVAILABLE", unavailable.code());
        assertEquals(0, interpreterCalls[0]);
    }

    @Test
    void routerAndDemandMustUseTheSamePinnedInstructionDigest() {
        CurrentInstruction routerInstruction = new CurrentInstruction("draw a flow");
        CurrentInstruction demandInstruction = new CurrentInstruction("use a file");
        TurnClassificationUnavailable unavailable = assertInstanceOf(
                TurnClassificationUnavailable.class,
                service(
                        new SemanticIntentReady(new SemanticIntent(
                                SemanticAction.CREATE, OutputIntent.DRAWING,
                                TargetNeed.NOT_REQUIRED, "flowchart", "none")),
                        new SourceDemandProposalReady(noSource(input(demandInstruction, Optional.empty()))))
                        .classify(routerInput(routerInstruction), input(demandInstruction, Optional.empty())));

        assertEquals("CLASSIFICATION_INPUT_DIGEST_MISMATCH", unavailable.code());
    }

    @Test
    void routerAndDemandMustShareThePinnedReadSetIdentity() {
        CurrentInstruction instruction = new CurrentInstruction("draw a flow");
        SemanticRouterInput router = routerInput(instruction);
        RestrictedSourceDemandInput demand = input(instruction, Optional.empty())
                .withModelInputBinding(ModelInputBinding.bound(
                        new TurnKey("owner-1", "conversation-1", "turn-1"),
                        "b".repeat(64), input(instruction, Optional.empty()).inputDigest()));

        TurnClassificationUnavailable unavailable = assertInstanceOf(
                TurnClassificationUnavailable.class,
                service(
                        new SemanticIntentReady(new SemanticIntent(
                                SemanticAction.CREATE, OutputIntent.DRAWING,
                                TargetNeed.NOT_REQUIRED, "flowchart", "none")),
                        new SourceDemandProposalReady(noSource(demand)))
                        .classify(router, demand));

        assertEquals("CLASSIFICATION_MODEL_INPUT_BINDING_INVALID", unavailable.code());
    }

    private TurnClassificationService service(
            SemanticIntentOutcome intent,
            SourceDemandProposalOutcome proposal
    ) {
        return new TurnClassificationService(
                input -> intent,
                input -> proposal,
                new SourceDemandResolver(),
                DemandResolutionPolicy.m2Default());
    }

    private SemanticRouterInput routerInput(CurrentInstruction instruction) {
        SemanticRouterInput input = new SemanticRouterInput(
                instruction, new RouterContextView(true, false, 0, false, false));
        return input.withModelInputBinding(ModelInputBinding.bound(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                "a".repeat(64), input.inputDigest()));
    }

    private RestrictedSourceDemandInput input(
            CurrentInstruction instruction,
            Optional<String> membership,
            OpaqueConversationFileRef... attachments
    ) {
        RestrictedSourceDemandInput input = new RestrictedSourceDemandInput(
                instruction, List.of(attachments), membership, Set.of());
        return input.withModelInputBinding(ModelInputBinding.bound(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                "a".repeat(64), input.inputDigest()));
    }

    private NoSourceDemandProposal noSource(RestrictedSourceDemandInput input) {
        return new NoSourceDemandProposal(evidence(input), "plain");
    }

    private ProposalEvidence evidence(RestrictedSourceDemandInput input) {
        CurrentInstruction instruction = input.instruction();
        return new ProposalEvidence(
                List.of(new CurrentInstructionSpan(
                        0, instruction.value().length(),
                        instruction.spanDigest(0, instruction.value().length()))),
                Confidence.HIGH,
                Optional.empty(), input.inputDigest(),
                DemandResolutionPolicy.m2Default().modelVersion(),
                DemandResolutionPolicy.m2Default().policyVersion());
    }
}
