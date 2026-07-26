package org.zipp.ai.application.turn.planning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirectCompositePlannerTest {

    private static final TurnKey TURN = new TurnKey("owner-1", "conversation-1", "turn-1");
    private static final PlanningLineageFingerprint LINEAGE =
            new PlanningLineageFingerprint("c".repeat(64));
    private static final String CONTEXT_DIGEST = "a".repeat(64);
    private static final String INPUT_DIGEST = "b".repeat(64);

    private final DirectCompositePlanner planner = new DirectCompositePlanner();

    @Test
    void oneCurrentMessageDirectCandidateBuildsRequiredDirectPlan() {
        SourceProbeCommand.Direct command = directCommand(SourceDemandKind.CURRENT_MESSAGE_DIRECT_REQUIRED);
        DirectCandidateFact direct = direct(command.binding(), "image-v1", "choice-1");

        SourcePlanDecision.SourceReady ready = assertInstanceOf(
                SourcePlanDecision.SourceReady.class,
                planner.plan(command, available(command.binding(),
                        new SourceAvailability.SingleRole(
                                new RoleAvailability.DirectAvailable(List.of(direct))))));

        SourceAwareDrawPlan.Direct plan = assertInstanceOf(
                SourceAwareDrawPlan.Direct.class, ready.bound().plan());
        assertEquals(direct, plan.direct().candidate());
        assertInstanceOf(SourceExecutionEntry.Primary.class, ready.bound().entry());
        assertEquals(LINEAGE, ready.bound().identity().lineage());
    }

    @Test
    void missingDirectFailsClosedAndMultipleImagesRequireClarification() {
        SourceProbeCommand.Direct command = directCommand(SourceDemandKind.CURRENT_MESSAGE_DIRECT_REQUIRED);

        SourcePlanDecision.PlanningBlocked missing = assertInstanceOf(
                SourcePlanDecision.PlanningBlocked.class,
                planner.plan(command, available(command.binding(),
                        new SourceAvailability.SingleRole(new RoleAvailability.Unavailable(
                                SourceRole.DIRECT, RoleUnavailability.NO_MATCH)))));
        assertEquals("DIRECT_SOURCE_MISSING", missing.reason());

        SourcePlanDecision.NeedClarification ambiguous = assertInstanceOf(
                SourcePlanDecision.NeedClarification.class,
                planner.plan(command, available(command.binding(),
                        new SourceAvailability.SingleRole(
                                new RoleAvailability.DirectAvailable(List.of(
                                        direct(command.binding(), "image-v1", "choice-1"),
                                        direct(command.binding(), "image-v2", "choice-2")))))));
        assertEquals("AMBIGUOUS_DIRECT_IMAGE", ambiguous.code());
        assertEquals(List.of("choice-1", "choice-2"), ambiguous.opaqueCandidateRefs());
    }

    @ParameterizedTest
    @EnumSource(value = RoleUnavailability.class, names = {
            "NO_MATCH", "PROCESSING", "DEPENDENCY_UNAVAILABLE"
    })
    void optionalCompositeMayUseOnlyItsSignedDirectBranch(RoleUnavailability reason) {
        SourceProbeCommand.OptionalComposite command =
                (SourceProbeCommand.OptionalComposite) SourceProbeCommand.from(
                        requirement(SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL));
        DirectCandidateFact direct = direct(command.binding(), "image-v1", "choice-1");

        SourcePlanDecision.DirectOnlyReady ready = assertInstanceOf(
                SourcePlanDecision.DirectOnlyReady.class,
                planner.plan(command, available(command.binding(),
                        new SourceAvailability.Composite(
                                new RoleAvailability.DirectAvailable(List.of(direct)),
                                new RoleAvailability.Unavailable(SourceRole.RETRIEVAL, reason)))));

        SourceAwareDrawPlan.OptionalComposite plan = assertInstanceOf(
                SourceAwareDrawPlan.OptionalComposite.class, ready.bound().plan());
        SourceExecutionEntry.SignedDirectOnly entry = assertInstanceOf(
                SourceExecutionEntry.SignedDirectOnly.class, ready.bound().entry());
        assertEquals(plan.validatedDirectOnlyFallback().branchId(), entry.branchId());
        assertEquals(DirectSourceReusePolicy.EXCLUDE_PRIMARY_FROM_RETRIEVAL,
                plan.reusePolicy());
    }

    @ParameterizedTest
    @EnumSource(value = RoleUnavailability.class, names = {
            "AUTHORIZATION_VIOLATION", "REQUIRED_CONFLICT"
    })
    void optionalCompositeDoesNotFallbackForTerminalRoleFacts(RoleUnavailability reason) {
        SourceProbeCommand.OptionalComposite command =
                (SourceProbeCommand.OptionalComposite) SourceProbeCommand.from(
                        requirement(SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL));

        assertInstanceOf(SourcePlanDecision.PlanningBlocked.class,
                planner.plan(command, available(command.binding(),
                        new SourceAvailability.Composite(
                                new RoleAvailability.DirectAvailable(List.of(
                                        direct(command.binding(), "image-v1", "choice-1"))),
                                new RoleAvailability.Unavailable(SourceRole.RETRIEVAL, reason)))));
    }

    @Test
    void optionalCompositeHitKeepsSignedFallbackForLaterEvidenceFailure() {
        SourceProbeCommand.OptionalComposite command =
                (SourceProbeCommand.OptionalComposite) SourceProbeCommand.from(
                        requirement(SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL));
        SourcePlanDecision.SourceReady primary = assertInstanceOf(
                SourcePlanDecision.SourceReady.class,
                planner.plan(command, compositeAvailable(command.binding())));

        SourcePlanDecision.DirectOnlyReady fallback =
                new OptionalCompositeFallbackPlanner().authorize(
                        primary,
                        new OptionalEvidenceOutcome.FallbackEligible(
                                FallbackReason.EVIDENCE_INSUFFICIENT));

        SourceAwareDrawPlan.OptionalComposite plan =
                (SourceAwareDrawPlan.OptionalComposite) fallback.bound().plan();
        SourceExecutionEntry.SignedDirectOnly entry =
                (SourceExecutionEntry.SignedDirectOnly) fallback.bound().entry();
        assertEquals(primary.bound().identity(), fallback.bound().identity());
        assertEquals(plan.validatedDirectOnlyFallback().branchId(), entry.branchId());
        assertEquals(FallbackReason.EVIDENCE_INSUFFICIENT, entry.reason());
    }

    @Test
    void requiredCompositeNeverProducesDirectOnlyOnRetrievalFailure() {
        SourceProbeCommand.RequiredComposite command =
                (SourceProbeCommand.RequiredComposite) SourceProbeCommand.from(
                        requirement(SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_REQUIRED));

        SourcePlanDecision.PlanningBlocked blocked = assertInstanceOf(
                SourcePlanDecision.PlanningBlocked.class,
                planner.plan(command, available(command.binding(),
                        new SourceAvailability.Composite(
                                new RoleAvailability.DirectAvailable(List.of(
                                        direct(command.binding(), "image-v1", "choice-1"))),
                                new RoleAvailability.Unavailable(
                                        SourceRole.RETRIEVAL,
                                        RoleUnavailability.DEPENDENCY_UNAVAILABLE)))));

        assertEquals("REQUIRED_RETRIEVAL_DEPENDENCY_UNAVAILABLE", blocked.reason());
    }

    @Test
    void globalProbeFailureAndSwappedCandidateBindingStopComposite() {
        SourceProbeCommand.OptionalComposite command =
                (SourceProbeCommand.OptionalComposite) SourceProbeCommand.from(
                        requirement(SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL));
        assertInstanceOf(SourcePlanDecision.PlanningBlocked.class,
                planner.plan(command, new SourceProbeOutcome.Unavailable(
                        command.binding(), SourceProbeOutcome.Unavailability.DEPENDENCY_UNAVAILABLE)));

        SourceProbeCommand.OptionalComposite other =
                (SourceProbeCommand.OptionalComposite) SourceProbeCommand.from(
                        requirement(new TurnKey("owner-2", "conversation-2", "turn-2"),
                                SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL));
        SourceAvailability swapped = new SourceAvailability.Composite(
                new RoleAvailability.DirectAvailable(List.of(
                        direct(other.binding(), "image-v1", "choice-1"))),
                new RoleAvailability.RetrievalAvailable(List.of(
                        new RetrievalCandidateFact(command.binding(), "source-v1"))));

        SourcePlanDecision.PlanningBlocked blocked = assertInstanceOf(
                SourcePlanDecision.PlanningBlocked.class,
                planner.plan(command, available(command.binding(), swapped)));
        assertEquals("INVARIANT_BREACH", blocked.reason());
    }

    @Test
    void compositeRoleTagsCannotBeSwapped() {
        SourceProbeCommand.OptionalComposite command =
                (SourceProbeCommand.OptionalComposite) SourceProbeCommand.from(
                        requirement(SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL));

        assertThrows(IllegalArgumentException.class, () -> new SourceAvailability.Composite(
                new RoleAvailability.RetrievalAvailable(List.of(
                        new RetrievalCandidateFact(command.binding(), "source-v1"))),
                new RoleAvailability.DirectAvailable(List.of(
                        direct(command.binding(), "image-v1", "choice-1")))));
    }

    private SourceProbeCommand.Direct directCommand(SourceDemandKind kind) {
        return (SourceProbeCommand.Direct) SourceProbeCommand.from(requirement(kind));
    }

    private SourceProbeOutcome.Available compositeAvailable(SourceProbeBinding binding) {
        return available(binding, new SourceAvailability.Composite(
                new RoleAvailability.DirectAvailable(List.of(
                        direct(binding, "image-v1", "choice-1"))),
                new RoleAvailability.RetrievalAvailable(List.of(
                        new RetrievalCandidateFact(binding, "source-v1")))));
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
            String clarificationRef
    ) {
        return new DirectCandidateFact(
                binding,
                candidateRef,
                DirectCandidateOrigin.CURRENT_MESSAGE_ATTACHMENT,
                "observation-" + candidateRef,
                clarificationRef);
    }

    private PrePlanOutcome.SourcePlanningRequired requirement(SourceDemandKind kind) {
        return requirement(TURN, kind);
    }

    private PrePlanOutcome.SourcePlanningRequired requirement(TurnKey turn, SourceDemandKind kind) {
        String query = kind == SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL
                || kind == SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_REQUIRED
                ? "login architecture" : null;
        AcceptedSourceDemand accepted =
                new AcceptedSourceDemand(kind, List.of("file-1"), query);
        return new PrePlanOutcome.SourcePlanningRequired(
                turn,
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
}
