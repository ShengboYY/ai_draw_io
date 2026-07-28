package org.zipp.ai.application.turn.classification;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;
import org.zipp.ai.application.turn.RequestedDiagramSkill;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.demand.Confidence;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.demand.DemandResolutionPolicy;
import org.zipp.ai.application.turn.demand.RestrictedSourceDemandInput;
import org.zipp.ai.application.turn.demand.SourceDemandResolver;
import org.zipp.ai.application.turn.skill.DiagramSkillBinding;
import org.zipp.ai.application.turn.skill.DiagramSkillCatalogSnapshot;
import org.zipp.ai.application.turn.skill.DiagramSkillSelectionSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SemanticTurnClassificationTest {

    @Test
    void oneRouterCallProducesAPlainPlanAfterDeterministicPolicy() {
        CurrentInstruction instruction = new CurrentInstruction("draw a login flow");
        RestrictedSourceDemandInput sourceInput = sourceInput(instruction, Optional.empty());
        AtomicInteger routerCalls = new AtomicInteger();
        SemanticIntent intent = new SemanticIntent(
                SemanticAction.CREATE,
                OutputIntent.DRAWING,
                TargetNeed.NOT_REQUIRED,
                "flowchart",
                "none",
                SemanticSourceIntent.none());

        TurnClassificationOutcome outcome = service(input -> {
            routerCalls.incrementAndGet();
            return new SemanticIntentReady(intent);
        }).classify(routerInput(instruction, sourceInput), sourceInput);

        TurnClassificationReady ready = assertInstanceOf(TurnClassificationReady.class, outcome);
        PlainDrawPlanReady plan = assertInstanceOf(
                PlainDrawPlanReady.class, new PlainDrawPlanFactory().create(ready.classification()));
        assertEquals(org.zipp.ai.application.turn.PlainDrawAction.CREATE, plan.plan().action());
        assertEquals("draw a login flow", plan.plan().instruction());
        assertEquals(1, routerCalls.get());
    }

    @Test
    void requiredAttachmentIntentCannotEnterPlainPlan() {
        CurrentInstruction instruction = new CurrentInstruction("use the attached specification");
        RestrictedSourceDemandInput sourceInput = sourceInput(
                instruction,
                Optional.of("chartbook-1"),
                new OpaqueConversationFileRef("file-1"));
        SemanticIntent intent = new SemanticIntent(
                SemanticAction.EDIT,
                OutputIntent.DRAWING,
                TargetNeed.CANVAS_REQUIRED,
                "flowchart",
                "source-branch-skill-is-not-admitted-yet",
                new SemanticSourceIntent(
                        SourceIntentKind.CURRENT_MESSAGE_ATTACHMENTS_REQUIRED,
                        Confidence.HIGH,
                        List.of("file-1"),
                        null,
                        "Use the current attachment."));

        TurnClassificationReady ready = assertInstanceOf(
                TurnClassificationReady.class,
                service(input -> new SemanticIntentReady(intent))
                        .classify(routerInput(instruction, sourceInput), sourceInput));

        PlainDrawPlanRejected rejected = assertInstanceOf(
                PlainDrawPlanRejected.class, new PlainDrawPlanFactory().create(ready.classification()));
        assertEquals("SOURCE_PLANNING_REQUIRED", rejected.code());
        assertEquals(List.of(), ready.classification().skillSelection().selectedSkills());
    }

    @Test
    void missingReferencedAttachmentBecomesClarificationWithoutSourceIo() {
        CurrentInstruction instruction = new CurrentInstruction("use the attachment");
        RestrictedSourceDemandInput sourceInput = sourceInput(instruction, Optional.empty());
        SemanticIntent intent = new SemanticIntent(
                SemanticAction.CREATE,
                OutputIntent.DRAWING,
                TargetNeed.NOT_REQUIRED,
                "flowchart",
                "none",
                new SemanticSourceIntent(
                        SourceIntentKind.CURRENT_MESSAGE_DIRECT_REQUIRED,
                        Confidence.HIGH,
                        List.of(),
                        null,
                        "The user refers to a missing attachment."));

        TurnClassificationReady ready = assertInstanceOf(
                TurnClassificationReady.class,
                service(input -> new SemanticIntentReady(intent))
                        .classify(routerInput(instruction, sourceInput), sourceInput));

        assertInstanceOf(
                org.zipp.ai.application.turn.demand.NeedsSourceClarification.class,
                ready.classification().demandResolution());
    }

    @Test
    void routerFailureDoesNotInvokeASecondModel() {
        CurrentInstruction instruction = new CurrentInstruction("draw");
        RestrictedSourceDemandInput sourceInput = sourceInput(instruction, Optional.empty());
        AtomicInteger routerCalls = new AtomicInteger();

        TurnClassificationUnavailable unavailable = assertInstanceOf(
                TurnClassificationUnavailable.class,
                service(input -> {
                    routerCalls.incrementAndGet();
                    return new SemanticIntentUnavailable("ROUTER_UNAVAILABLE");
                }).classify(routerInput(instruction, sourceInput), sourceInput));

        assertEquals("ROUTER_UNAVAILABLE", unavailable.code());
        assertEquals(1, routerCalls.get());
    }

    @Test
    void routerAndSourcePolicyMustUseTheSameInstruction() {
        CurrentInstruction routerInstruction = new CurrentInstruction("draw a flow");
        RestrictedSourceDemandInput routerSource =
                sourceInput(routerInstruction, Optional.empty());
        RestrictedSourceDemandInput differentSource =
                sourceInput(new CurrentInstruction("use a file"), Optional.empty());

        TurnClassificationUnavailable unavailable = assertInstanceOf(
                TurnClassificationUnavailable.class,
                service(input -> new SemanticIntentReady(new SemanticIntent(
                        SemanticAction.CREATE,
                        OutputIntent.DRAWING,
                        TargetNeed.NOT_REQUIRED,
                        "flowchart",
                        "none")))
                        .classify(routerInput(routerInstruction, routerSource), differentSource));

        assertEquals("CLASSIFICATION_INPUT_DIGEST_MISMATCH", unavailable.code());
    }

    @Test
    void routerCannotInventADifferentAttachmentProjection() {
        CurrentInstruction instruction = new CurrentInstruction("use this image");
        RestrictedSourceDemandInput sourceInput = sourceInput(
                instruction,
                Optional.empty(),
                new OpaqueConversationFileRef("file-1"));
        RestrictedSourceDemandInput differentSource = sourceInput(
                instruction,
                Optional.empty(),
                new OpaqueConversationFileRef("file-2"));

        TurnClassificationUnavailable unavailable = assertInstanceOf(
                TurnClassificationUnavailable.class,
                service(input -> new SemanticIntentReady(new SemanticIntent(
                        SemanticAction.CREATE,
                        OutputIntent.DRAWING,
                        TargetNeed.NOT_REQUIRED,
                        "flowchart",
                        "none")))
                        .classify(routerInput(instruction, sourceInput), differentSource));

        assertEquals("CLASSIFICATION_MODEL_INPUT_BINDING_INVALID", unavailable.code());
    }

    @Test
    void explicitUserSkillOverridesRouterSelectionAndIsPinnedIntoPlainPlan() {
        CurrentInstruction instruction = new CurrentInstruction("draw a custom login flow");
        RestrictedSourceDemandInput sourceInput = sourceInput(instruction, Optional.empty());
        DiagramSkillCatalogSnapshot catalog = catalog();
        SemanticIntent intent = new SemanticIntent(
                SemanticAction.CREATE,
                OutputIntent.DRAWING,
                TargetNeed.NOT_REQUIRED,
                "flowchart",
                "drawio-flowchart",
                SemanticSourceIntent.none());

        TurnClassificationReady ready = assertInstanceOf(
                TurnClassificationReady.class,
                service(input -> new SemanticIntentReady(intent)).classify(
                        routerInput(
                                instruction,
                                sourceInput,
                                catalog,
                                List.of(new RequestedDiagramSkill("custom-flow"))),
                        sourceInput));

        assertEquals(DiagramSkillSelectionSource.USER, ready.classification().skillSelection().source());
        assertEquals(List.of("custom-flow"), ready.classification().skillSelection().selectedSkills()
                .stream().map(DiagramSkillBinding::name).toList());
        PlainDrawPlanReady plan = assertInstanceOf(
                PlainDrawPlanReady.class, new PlainDrawPlanFactory().create(ready.classification()));
        assertEquals("flowchart", plan.plan().diagramType());
        assertEquals(List.of("drawio-xml-guide", "drawio-visual-design", "custom-flow"),
                plan.plan().skillSelection().requiredSkills().stream()
                        .map(DiagramSkillBinding::name).toList());
    }

    @Test
    void routerCannotSelectASkillThatWasNotOffered() {
        CurrentInstruction instruction = new CurrentInstruction("draw");
        RestrictedSourceDemandInput sourceInput = sourceInput(instruction, Optional.empty());
        SemanticIntent intent = new SemanticIntent(
                SemanticAction.CREATE,
                OutputIntent.DRAWING,
                TargetNeed.NOT_REQUIRED,
                "flowchart",
                "invented-skill",
                SemanticSourceIntent.none());

        TurnClassificationUnavailable unavailable = assertInstanceOf(
                TurnClassificationUnavailable.class,
                service(input -> new SemanticIntentReady(intent)).classify(
                        routerInput(instruction, sourceInput, catalog(), List.of()),
                        sourceInput));

        assertEquals("V2_SEMANTIC_SKILL_INVALID", unavailable.code());
    }

    private TurnClassificationService service(SemanticIntentRouterPort router) {
        return new TurnClassificationService(
                router,
                new SourceDemandResolver(),
                DemandResolutionPolicy.m2Default());
    }

    private SemanticRouterInput routerInput(
            CurrentInstruction instruction,
            RestrictedSourceDemandInput sourceInput
    ) {
        return routerInput(
                instruction,
                sourceInput,
                DiagramSkillCatalogSnapshot.empty(),
                List.of());
    }

    private SemanticRouterInput routerInput(
            CurrentInstruction instruction,
            RestrictedSourceDemandInput sourceInput,
            DiagramSkillCatalogSnapshot catalog,
            List<RequestedDiagramSkill> requestedSkills
    ) {
        SemanticRouterInput input = new SemanticRouterInput(
                instruction,
                new RouterContextView(true, false, 0, false, false))
                .withSourceContext(sourceInput)
                .withSkillContext(catalog, requestedSkills);
        return input.withModelInputBinding(ModelInputBinding.bound(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                "a".repeat(64),
                input.inputDigest()));
    }

    private DiagramSkillCatalogSnapshot catalog() {
        return new DiagramSkillCatalogSnapshot(
                true,
                "- custom-flow: custom\n- drawio-flowchart: standard\n",
                List.of(
                        skill("custom-flow", "flowchart", '1'),
                        skill("drawio-flowchart", "flowchart", '2')),
                List.of(
                        skill("drawio-xml-guide", "shared", '3'),
                        skill("drawio-visual-design", "shared", '4')),
                "f".repeat(64));
    }

    private DiagramSkillBinding skill(String name, String diagramType, char digestCharacter) {
        return new DiagramSkillBinding(name, diagramType, String.valueOf(digestCharacter).repeat(64));
    }

    private RestrictedSourceDemandInput sourceInput(
            CurrentInstruction instruction,
            Optional<String> membership,
            OpaqueConversationFileRef... attachments
    ) {
        return new RestrictedSourceDemandInput(
                instruction, List.of(attachments), membership, Set.of());
    }
}
