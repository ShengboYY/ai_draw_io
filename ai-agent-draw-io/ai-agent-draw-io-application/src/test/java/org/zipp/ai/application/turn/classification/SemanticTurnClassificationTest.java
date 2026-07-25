package org.zipp.ai.application.turn.classification;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;
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
                new SourceDemandProposalReady(noSource(instruction)))
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
                List.of("file-1"), null, evidence(instruction), "use attachment");
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
            return new SourceDemandProposalReady(noSource(input.instruction()));
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
                        new SourceDemandProposalReady(noSource(demandInstruction)))
                        .classify(routerInput(routerInstruction), input(demandInstruction, Optional.empty())));

        assertEquals("CLASSIFICATION_INPUT_DIGEST_MISMATCH", unavailable.code());
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
        return new SemanticRouterInput(instruction, new RouterContextView(true, false, 0, false, false));
    }

    private RestrictedSourceDemandInput input(
            CurrentInstruction instruction,
            Optional<String> membership,
            OpaqueConversationFileRef... attachments
    ) {
        return new RestrictedSourceDemandInput(
                instruction, List.of(attachments), membership, Set.of());
    }

    private NoSourceDemandProposal noSource(CurrentInstruction instruction) {
        return new NoSourceDemandProposal(evidence(instruction), "plain");
    }

    private ProposalEvidence evidence(CurrentInstruction instruction) {
        return new ProposalEvidence(
                List.of(new CurrentInstructionSpan(
                        0, instruction.value().length(),
                        instruction.spanDigest(0, instruction.value().length()))),
                Confidence.HIGH,
                Optional.empty());
    }
}
