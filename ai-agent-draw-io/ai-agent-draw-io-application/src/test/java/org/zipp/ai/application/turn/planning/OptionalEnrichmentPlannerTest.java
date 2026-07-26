package org.zipp.ai.application.turn.planning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.zipp.ai.application.turn.classification.OutputIntent;
import org.zipp.ai.application.turn.classification.SemanticAction;
import org.zipp.ai.application.turn.classification.SemanticIntent;
import org.zipp.ai.application.turn.classification.TargetNeed;
import org.zipp.ai.application.turn.demand.AcceptedSourceDemand;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.demand.ResolvedSourceDemand;
import org.zipp.ai.application.turn.demand.SourceDemandKind;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OptionalEnrichmentPlannerTest {

    private static final PlanningLineageFingerprint LINEAGE =
            new PlanningLineageFingerprint("c".repeat(64));
    private static final String CONTEXT_DIGEST = "a".repeat(64);
    private static final String INPUT_DIGEST = "b".repeat(64);

    private final OptionalEnrichmentPlanner planner = new OptionalEnrichmentPlanner();

    @Test
    void optionalCommandCarriesDeterministicFallbackBeforeProbe() {
        SourceProbeCommand.OptionalDiscovery first = assertInstanceOf(
                SourceProbeCommand.OptionalDiscovery.class,
                SourceProbeCommand.from(requirement(SourceDemandKind.OPTIONAL_DISCOVERY)));
        SourceProbeCommand.OptionalDiscovery second = assertInstanceOf(
                SourceProbeCommand.OptionalDiscovery.class,
                SourceProbeCommand.from(requirement(SourceDemandKind.OPTIONAL_DISCOVERY)));

        assertEquals(first.validatedProbeFallback(), second.validatedProbeFallback());
        assertEquals("draw a login flow", first.validatedProbeFallback().plan().instruction());
        assertEquals(LINEAGE, first.binding().lineage());
    }

    @ParameterizedTest
    @EnumSource(SourceProbeOutcome.Unavailability.class)
    void optionalNoMatchTimeoutAndDependencyFailureUseSignedProbeFallback(
            SourceProbeOutcome.Unavailability reason
    ) {
        SourceProbeCommand command =
                SourceProbeCommand.from(requirement(SourceDemandKind.OPTIONAL_DISCOVERY));

        SourcePlanDecision.ProbeFallbackReady fallback = assertInstanceOf(
                SourcePlanDecision.ProbeFallbackReady.class,
                planner.plan(command, new SourceProbeOutcome.Unavailable(command.binding(), reason)));

        SourceProbeCommand.OptionalDiscovery optional =
                (SourceProbeCommand.OptionalDiscovery) command;
        assertEquals(optional.validatedProbeFallback(), fallback.fallback());
        assertEquals(LINEAGE, fallback.lineage());
    }

    @Test
    void optionalHitBuildsRetrievalPlanWithTheSameSignedFallback() {
        SourceProbeCommand.OptionalDiscovery command =
                (SourceProbeCommand.OptionalDiscovery) SourceProbeCommand.from(
                        requirement(SourceDemandKind.OPTIONAL_DISCOVERY));

        SourcePlanDecision.OptionalRetrievalReady ready = assertInstanceOf(
                SourcePlanDecision.OptionalRetrievalReady.class,
                planner.plan(command,
                        new SourceProbeOutcome.Available(command.binding(), List.of("source-v1"))));

        assertEquals(List.of("source-v1"), ready.plan().candidateRefs());
        assertEquals(command.validatedProbeFallback(), ready.plan().validatedFallback());
    }

    @ParameterizedTest
    @EnumSource(SourceProbeOutcome.Unavailability.class)
    void requiredSourceNeverFallsBack(SourceProbeOutcome.Unavailability reason) {
        SourceProbeCommand.Required command = assertInstanceOf(
                SourceProbeCommand.Required.class,
                SourceProbeCommand.from(
                        requirement(SourceDemandKind.CURRENT_MESSAGE_RETRIEVAL_REQUIRED)));

        assertInstanceOf(SourcePlanDecision.PlanningBlocked.class,
                planner.plan(command,
                        new SourceProbeOutcome.Unavailable(command.binding(), reason)));
        assertFalse(Arrays.stream(SourceProbeCommand.Required.class.getMethods())
                .anyMatch(method -> method.getName().toLowerCase().contains("fallback")));
    }

    @Test
    void bindingMismatchAndTerminalProbeResultsFailClosed() {
        SourceProbeCommand command =
                SourceProbeCommand.from(requirement(SourceDemandKind.OPTIONAL_DISCOVERY));
        SourceProbeBinding wrongBinding = new SourceProbeBinding(
                command.binding().turn(),
                LINEAGE,
                command.binding().declarationDigest(),
                "d".repeat(64),
                INPUT_DIGEST);

        SourcePlanDecision.PlanningBlocked mismatch = assertInstanceOf(
                SourcePlanDecision.PlanningBlocked.class,
                planner.plan(command,
                        new SourceProbeOutcome.Unavailable(
                                wrongBinding, SourceProbeOutcome.Unavailability.NO_MATCH)));
        assertEquals("CAPABILITY_SCOPE_MISMATCH", mismatch.reason());
        assertInstanceOf(SourcePlanDecision.PlanningBlocked.class,
                planner.plan(command,
                        new SourceProbeOutcome.Terminal(command.binding(), "AUTHORIZATION_VIOLATION")));
        assertInstanceOf(SourcePlanDecision.PlanningBlocked.class,
                planner.plan(command,
                        new SourceProbeOutcome.Cancelled(command.binding(), "USER_CANCELLED")));
    }

    private PrePlanOutcome.SourcePlanningRequired requirement(SourceDemandKind kind) {
        AcceptedSourceDemand accepted = kind == SourceDemandKind.OPTIONAL_DISCOVERY
                ? new AcceptedSourceDemand(kind, List.of(), "login architecture")
                : new AcceptedSourceDemand(kind, List.of("file-1"), null);
        return new PrePlanOutcome.SourcePlanningRequired(
                new org.zipp.ai.application.turn.TurnKey(
                        "owner-1", "conversation-1", "turn-1"),
                new CurrentInstruction("draw a login flow"),
                new SemanticIntent(
                        SemanticAction.CREATE,
                        OutputIntent.DRAWING,
                        TargetNeed.NOT_REQUIRED,
                        "flowchart",
                        "none"),
                new ResolvedSourceDemand(accepted, List.of()),
                accepted,
                LINEAGE,
                CONTEXT_DIGEST,
                INPUT_DIGEST);
    }
}
