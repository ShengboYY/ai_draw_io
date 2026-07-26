package org.zipp.ai.application.turn.execution;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.SourceAwarePreparationPort;
import org.zipp.ai.application.turn.SourceExecutionBindingOutcome;
import org.zipp.ai.application.turn.SourceExecutionBindingPort;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnInputBindingDigestCalculator;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.context.AbsentContext;
import org.zipp.ai.application.turn.context.AvailableContext;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextDiagnostics;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.context.ConversationContext;
import org.zipp.ai.application.turn.context.CurrentMessageAttachmentsContext;
import org.zipp.ai.application.turn.context.CurrentRequestContext;
import org.zipp.ai.application.turn.context.TrustedCanvasContext;
import org.zipp.ai.application.turn.context.ValidatedSelectionContext;
import org.zipp.ai.application.turn.classification.OutputIntent;
import org.zipp.ai.application.turn.classification.SemanticAction;
import org.zipp.ai.application.turn.classification.SemanticIntent;
import org.zipp.ai.application.turn.classification.TargetNeed;
import org.zipp.ai.application.turn.demand.AcceptedSourceDemand;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.demand.ResolvedSourceDemand;
import org.zipp.ai.application.turn.demand.SourceDemandKind;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.application.turn.planning.PrePlanOutcome;
import org.zipp.ai.application.turn.planning.SourceProbeOutcome;
import org.zipp.ai.application.turn.planning.SourceProbePort;
import org.zipp.ai.application.turn.planning.DirectCompositePlanner;
import org.zipp.ai.application.turn.planning.OptionalEnrichmentPlanner;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DefaultSourceAwareTurnExecutionTest {

    @Test
    void requiredDirectProbeFailureIsRejectedWithoutPreparationOrBinding() {
        UserTurnCommand command = command();
        FencedAttempt attempt = attempt(command);
        BaseTurnContext context = context(command);
        ContextReadSet readSet = readSet(attempt.contextMessageHighWater());
        TurnRouteDecision.SourcePlanning decision = sourceDecision(attempt, readSet);
        TurnV2PreHandlerOutcome.Ready prepared = new TurnV2PreHandlerOutcome.Ready(
                attempt, context, readSet, decision, checkpoint(readSet, attempt));
        AtomicInteger preparations = new AtomicInteger();
        AtomicInteger pins = new AtomicInteger();

        SourceProbePort probe = sourceProbe -> new SourceProbeOutcome.Unavailable(
                sourceProbe.binding(), SourceProbeOutcome.Unavailability.NO_MATCH);
        SourceAwarePreparationPort preparation = request -> {
            preparations.incrementAndGet();
            throw new AssertionError("missing Direct source must stop before preparation");
        };
        SourceExecutionBindingPort binding = (ignoredAttempt, ignoredBinding) -> {
            pins.incrementAndGet();
            return new SourceExecutionBindingOutcome.Pinned();
        };

        TurnV2ExecutionOutcome.NotDispatched outcome = assertInstanceOf(
                TurnV2ExecutionOutcome.NotDispatched.class,
                execution(probe, preparation, binding).execute(
                        attempt, command, prepared, ignored -> { }));

        assertEquals("SOURCE_PROBE_NO_MATCH", outcome.code());
        assertEquals(0, preparations.get());
        assertEquals(0, pins.get());
    }

    private DefaultSourceAwareTurnExecution execution(
            SourceProbePort probe,
            SourceAwarePreparationPort preparation,
            SourceExecutionBindingPort binding
    ) {
        return new DefaultSourceAwareTurnExecution(
                probe,
                new DirectCompositePlanner(),
                new OptionalEnrichmentPlanner(),
                preparation,
                binding,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private TurnRouteDecision.SourcePlanning sourceDecision(
            FencedAttempt attempt,
            ContextReadSet readSet
    ) {
        AcceptedSourceDemand accepted = new AcceptedSourceDemand(
                SourceDemandKind.CURRENT_MESSAGE_DIRECT_REQUIRED, List.of("file-1"), null);
        PrePlanOutcome.SourcePlanningRequired required = new PrePlanOutcome.SourcePlanningRequired(
                attempt.key(),
                new CurrentInstruction("rebuild the attachment"),
                new SemanticIntent(SemanticAction.CREATE, OutputIntent.DRAWING,
                        TargetNeed.NOT_REQUIRED, "unknown", "none"),
                new ResolvedSourceDemand(accepted, List.of()),
                accepted,
                new PlanningLineageFingerprint("a".repeat(64)),
                readSet.digest(),
                attempt.inputBindingDigest());
        return new TurnRouteDecision.SourcePlanning(required);
    }

    private UserTurnCommand command() {
        return new UserTurnCommand(
                "turn-1", "conversation-1", "diagram-1", "client-1",
                "rebuild the attachment", "session-1", TurnDeclarations.empty());
    }

    private FencedAttempt attempt(UserTurnCommand command) {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", command.turnId()),
                AttemptLease.fromDatabaseClock(
                        "attempt-1", 1,
                        Instant.parse("2026-07-26T00:00:00Z"),
                        Instant.parse("2026-07-26T00:00:30Z"), 30_000),
                2,
                TurnInputBindingDigestCalculator.current(command),
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy"));
    }

    private BaseTurnContext context(UserTurnCommand command) {
        return new BaseTurnContext(
                new CurrentRequestContext(command.turnId(), command.diagramId(),
                        new CurrentInstruction(command.content())),
                new AvailableContext<>(new CurrentMessageAttachmentsContext(
                        "b".repeat(64), List.of()), "attachments"),
                new AbsentContext<>("no clarification"),
                new AvailableContext<>(new TrustedCanvasContext(false, 0, 0, ""), "canvas"),
                new AvailableContext<>(new ValidatedSelectionContext(false, 0), "selection"),
                new AvailableContext<>(new ConversationContext(List.of(), ""), "conversation"),
                new AbsentContext<>("no membership"),
                new AbsentContext<>("no profile"),
                new AbsentContext<>("no memory"),
                new ContextDiagnostics(List.of()));
    }

    private ContextReadSet readSet(long highWater) {
        return ContextReadSet.create(
                1,
                highWater,
                ContextSlicePin.absent(ContextSlice.SUMMARY, "NO_CANVAS"),
                ContextSlicePin.absent(ContextSlice.MEMBERSHIP, "NO_ACTIVE_CHARTBOOK"),
                ContextSlicePin.absent(ContextSlice.PROFILE, "PROFILE_NOT_AVAILABLE"),
                ContextSlicePin.absent(ContextSlice.MEMORY, "NO_CONFIRMED_MEMORY"));
    }

    private org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpoint checkpoint(
            ContextReadSet readSet,
            FencedAttempt attempt
    ) {
        return org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpoint.create(
                1, readSet.digest(), attempt.inputBindingDigest(), "SOURCE_PLANNING", "{}");
    }

}
