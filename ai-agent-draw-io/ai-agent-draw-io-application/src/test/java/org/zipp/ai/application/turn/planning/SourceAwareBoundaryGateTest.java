package org.zipp.ai.application.turn.planning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.zipp.ai.application.turn.SourceCommitBinding;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.classification.OutputIntent;
import org.zipp.ai.application.turn.classification.SemanticAction;
import org.zipp.ai.application.turn.classification.SemanticIntent;
import org.zipp.ai.application.turn.classification.TargetNeed;
import org.zipp.ai.application.turn.demand.AcceptedSourceDemand;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.demand.ResolvedSourceDemand;
import org.zipp.ai.application.turn.demand.SourceDemandKind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Isolated M6 gate tests for the source-aware plan boundary.
 *
 * <p>These tests deliberately stop at the planner/freeze contract. They do not
 * create a production assignment or dispatch a source-aware runtime.</p>
 */
class SourceAwareBoundaryGateTest {

    private static final TurnKey TURN =
            new TurnKey("owner-1", "conversation-1", "turn-1");
    private static final PlanningLineageFingerprint LINEAGE =
            new PlanningLineageFingerprint("c".repeat(64));
    private static final String CONTEXT_DIGEST = "a".repeat(64);
    private static final String INPUT_DIGEST = "b".repeat(64);

    private final DirectCompositePlanner planner = new DirectCompositePlanner();

    @ParameterizedTest
    @EnumSource(DirectCandidateOrigin.class)
    void plannerKeepsTheOriginAndFullBindingOnTheSealedDirectSelector(
            DirectCandidateOrigin origin
    ) {
        SourceProbeCommand.Direct command = (SourceProbeCommand.Direct)
                SourceProbeCommand.from(requirement(
                        SourceDemandKind.CURRENT_MESSAGE_DIRECT_REQUIRED));
        DirectCandidateFact candidate = direct(command.binding(), "candidate-1", origin);

        SourcePlanDecision.SourceReady ready = assertInstanceOf(
                SourcePlanDecision.SourceReady.class,
                planner.plan(command, available(command.binding(),
                        new SourceAvailability.SingleRole(
                                new RoleAvailability.DirectAvailable(List.of(candidate))))));
        SourceAwareDrawPlan.Direct plan = assertInstanceOf(
                SourceAwareDrawPlan.Direct.class, ready.bound().plan());

        // The selector must carry the Probe fact itself, not re-resolve by ref or name.
        assertSame(candidate, plan.direct().candidate());
        assertEquals(origin, plan.direct().candidate().origin());
        assertEquals(command.binding(), plan.direct().candidate().binding());
        assertEquals(command.binding().lineage(), ready.bound().identity().lineage());
    }

    @ParameterizedTest
    @EnumSource(RoleUnavailability.class)
    void missingDirectNeverDegradesOptionalOrRequiredCompositeToRetrieval(
            RoleUnavailability reason
    ) {
        for (SourceDemandKind kind : List.of(
                SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL,
                SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_REQUIRED)) {
            SourceProbeCommand command = SourceProbeCommand.from(requirement(kind));
            SourcePlanDecision.PlanningBlocked blocked = assertInstanceOf(
                    SourcePlanDecision.PlanningBlocked.class,
                    planner.plan(command, available(command.binding(),
                            new SourceAvailability.Composite(
                                    new RoleAvailability.Unavailable(
                                            SourceRole.DIRECT, reason),
                                    new RoleAvailability.RetrievalAvailable(List.of(
                                            retrieval(command.binding(), "source-1")))))));

            assertEquals(expectedDirectCode(reason), blocked.reason());
        }
    }

    @ParameterizedTest
    @EnumSource(RoleUnavailability.class)
    void requiredCompositeNeverProducesPartialPlanWhenRetrievalIsUnavailable(
            RoleUnavailability reason
    ) {
        SourceProbeCommand.RequiredComposite command =
                (SourceProbeCommand.RequiredComposite) SourceProbeCommand.from(
                        requirement(SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_REQUIRED));

        SourcePlanDecision.PlanningBlocked blocked = assertInstanceOf(
                SourcePlanDecision.PlanningBlocked.class,
                planner.plan(command, available(command.binding(),
                        new SourceAvailability.Composite(
                                new RoleAvailability.DirectAvailable(List.of(
                                        direct(command.binding(), "candidate-1",
                                                DirectCandidateOrigin.CURRENT_MESSAGE_ATTACHMENT))),
                                new RoleAvailability.Unavailable(
                                        SourceRole.RETRIEVAL, reason)))));

        assertEquals("REQUIRED_RETRIEVAL_" + reason.name(), blocked.reason());
    }

    @Test
    void directAndRetrievalMembershipBothContributeToPlanIdentity() {
        BoundSourcePlan first = compositePlan("candidate-1", "source-1");
        BoundSourcePlan changedDirect = compositePlan("candidate-2", "source-1");
        BoundSourcePlan changedRetrieval = compositePlan("candidate-1", "source-2");

        // Freeze must carry the exact planner identity; later stages cannot look up a new graph.
        SourceCommitBinding frozen = new SourceCommitBinding(
                first.identity(), "snapshot-1", "1".repeat(64), "2".repeat(64));
        assertSame(first.identity(), frozen.planIdentity());
        assertNotEquals(first.identity().planFingerprint(),
                changedDirect.identity().planFingerprint());
        assertNotEquals(first.identity().planFingerprint(),
                changedRetrieval.identity().planFingerprint());
    }

    @Test
    void optionalDirectOnlyFallbackKeepsRootIdentityAndSignedBranch() {
        SourceProbeCommand.OptionalComposite command =
                (SourceProbeCommand.OptionalComposite) SourceProbeCommand.from(
                        requirement(SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL));
        DirectCandidateFact candidate = direct(
                command.binding(), "candidate-1", DirectCandidateOrigin.NAMED_SOURCE);
        SourcePlanDecision.SourceReady primary = assertInstanceOf(
                SourcePlanDecision.SourceReady.class,
                planner.plan(command, available(command.binding(),
                        new SourceAvailability.Composite(
                                new RoleAvailability.DirectAvailable(List.of(candidate)),
                                new RoleAvailability.RetrievalAvailable(List.of(
                                        retrieval(command.binding(), "source-1")))))));

        SourcePlanDecision.DirectOnlyReady fallback =
                new OptionalCompositeFallbackPlanner().authorize(
                        primary,
                        new OptionalEvidenceOutcome.FallbackEligible(
                                FallbackReason.EVIDENCE_INSUFFICIENT));

        SourceAwareDrawPlan.OptionalComposite plan = assertInstanceOf(
                SourceAwareDrawPlan.OptionalComposite.class, fallback.bound().plan());
        SourceExecutionEntry.SignedDirectOnly entry = assertInstanceOf(
                SourceExecutionEntry.SignedDirectOnly.class, fallback.bound().entry());
        assertSame(primary.bound().identity(), fallback.bound().identity());
        assertEquals(plan.validatedDirectOnlyFallback().branchId(), entry.branchId());
        assertEquals(FallbackReason.EVIDENCE_INSUFFICIENT, entry.reason());
    }

    private BoundSourcePlan compositePlan(String directRef, String retrievalRef) {
        SourceProbeCommand.OptionalComposite command =
                (SourceProbeCommand.OptionalComposite) SourceProbeCommand.from(
                        requirement(SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL));
        return assertInstanceOf(
                SourcePlanDecision.SourceReady.class,
                planner.plan(command, available(command.binding(),
                        new SourceAvailability.Composite(
                                new RoleAvailability.DirectAvailable(List.of(
                                        direct(command.binding(), directRef,
                                                DirectCandidateOrigin.CURRENT_MESSAGE_ATTACHMENT))),
                                new RoleAvailability.RetrievalAvailable(List.of(
                                        retrieval(command.binding(), retrievalRef))))))).bound();
    }

    private SourceProbeOutcome.Available available(
            SourceProbeBinding binding,
            SourceAvailability availability
    ) {
        return new SourceProbeOutcome.Available(binding, availability);
    }

    private DirectCandidateFact direct(
            SourceProbeBinding binding,
            String candidateRef,
            DirectCandidateOrigin origin
    ) {
        return new DirectCandidateFact(
                binding,
                candidateRef,
                origin,
                "observation-" + candidateRef,
                "clarification-" + candidateRef);
    }

    private RetrievalCandidateFact retrieval(
            SourceProbeBinding binding,
            String candidateRef
    ) {
        return new RetrievalCandidateFact(binding, candidateRef);
    }

    private PrePlanOutcome.SourcePlanningRequired requirement(SourceDemandKind kind) {
        AcceptedSourceDemand accepted = new AcceptedSourceDemand(
                kind,
                List.of("file-1"),
                kind == SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL
                        || kind == SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_REQUIRED
                        ? "login architecture" : null);
        return new PrePlanOutcome.SourcePlanningRequired(
                TURN,
                new CurrentInstruction("rebuild the attachment"),
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

    private String expectedDirectCode(RoleUnavailability reason) {
        return reason == RoleUnavailability.NO_MATCH
                ? "DIRECT_SOURCE_MISSING"
                : "DIRECT_" + reason.name();
    }
}
