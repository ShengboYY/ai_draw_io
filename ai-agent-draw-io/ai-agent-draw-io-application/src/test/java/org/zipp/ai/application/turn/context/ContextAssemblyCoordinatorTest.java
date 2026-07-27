package org.zipp.ai.application.turn.context;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnInputBindingDigestCalculator;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.UserTurnCommand;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextAssemblyCoordinatorTest {

    @Test
    void foundReadSetSkipsLiveCandidateAndMaterializesTheWinner() {
        UserTurnCommand command = command("draw a flow");
        FencedAttempt attempt = attempt(command, 17);
        ContextReadSet winner = readSet(17, "profile-v1");
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicReference<ContextReadSet> materialized = new AtomicReference<>();

        DefaultBaseTurnContextAssembler assembler = new DefaultBaseTurnContextAssembler(
                ignored -> new ContextReadSetLoadOutcome.Found(winner),
                (ignored, ignoredProposal) -> new ContextReadSetOutcome.Retry(),
                (ignored, ignoredCommand) -> {
                    candidateCalls.incrementAndGet();
                    return new ContextCandidateLoadOutcome.Retry();
                },
                (ignored, ignoredCommand, readSet) -> {
                    materialized.set(readSet);
                    return new ContextMaterializationOutcome.Ready(slices());
                });

        ContextAssemblyOutcome.Ready ready = assertInstanceOf(
                ContextAssemblyOutcome.Ready.class, assembler.assemble(attempt, command));

        assertEquals(winner, ready.readSet());
        assertEquals(winner, materialized.get());
        assertEquals(0, candidateCalls.get());
        assertEquals("draw a flow", ready.context().request().instruction().value());
    }

    @Test
    void preparationExposesTheSamePinnedReadSetToTheDecisionStage() {
        UserTurnCommand command = command("draw a flow");
        FencedAttempt attempt = attempt(command, 17);
        ContextReadSet winner = readSet(17, "profile-v1");

        DefaultBaseTurnContextAssembler assembler = new DefaultBaseTurnContextAssembler(
                ignored -> new ContextReadSetLoadOutcome.Found(winner),
                (ignored, ignoredProposal) -> new ContextReadSetOutcome.Retry(),
                (ignored, ignoredCommand) -> new ContextCandidateLoadOutcome.Retry(),
                (ignored, ignoredCommand, ignoredReadSet) ->
                        new ContextMaterializationOutcome.Ready(slices()));

        ContextPreparationOutcome.Ready prepared = assertInstanceOf(
                ContextPreparationOutcome.Ready.class, assembler.prepareBeforeRouter(attempt, command));

        // The router must consume the winner selected by assembly, without a second live read.
        assertEquals(winner, prepared.readSet());
        assertEquals(winner.digest(), prepared.readSet().digest());
        assertEquals(winner.messageHighWater(), prepared.readSet().messageHighWater());
    }

    @Test
    void missingReadSetPinsCandidateBeforeExactMaterialization() {
        UserTurnCommand command = command("draw a flow");
        FencedAttempt attempt = attempt(command, 17);
        ContextReadSet candidate = readSet(17, "profile-v1");
        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger commitCalls = new AtomicInteger();

        DefaultBaseTurnContextAssembler assembler = new DefaultBaseTurnContextAssembler(
                ignored -> new ContextReadSetLoadOutcome.Missing(),
                (ignored, proposal) -> {
                    commitCalls.incrementAndGet();
                    return new ContextReadSetOutcome.Pinned(proposal.value());
                },
                (ignored, ignoredCommand) -> {
                    candidateCalls.incrementAndGet();
                    return new ContextCandidateLoadOutcome.Ready(new ContextCandidate(candidate));
                },
                (ignored, ignoredCommand, readSet) -> {
                    assertEquals(candidate, readSet);
                    return new ContextMaterializationOutcome.Ready(slices());
                });

        assertInstanceOf(ContextAssemblyOutcome.Ready.class, assembler.assemble(attempt, command));
        assertEquals(1, candidateCalls.get());
        assertEquals(1, commitCalls.get());
    }

    @Test
    void casRetryReloadsTheWinnerInsteadOfUsingTheLosingCandidate() {
        UserTurnCommand command = command("draw a flow");
        FencedAttempt attempt = attempt(command, 17);
        ContextReadSet candidate = readSet(17, "profile-loser");
        ContextReadSet winner = readSet(17, "profile-winner");
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<ContextReadSet> materialized = new AtomicReference<>();

        DefaultBaseTurnContextAssembler assembler = new DefaultBaseTurnContextAssembler(
                ignored -> loads.getAndIncrement() == 0
                        ? new ContextReadSetLoadOutcome.Missing()
                        : new ContextReadSetLoadOutcome.Found(winner),
                (ignored, ignoredProposal) -> new ContextReadSetOutcome.Retry(),
                (ignored, ignoredCommand) -> new ContextCandidateLoadOutcome.Ready(
                        new ContextCandidate(candidate)),
                (ignored, ignoredCommand, readSet) -> {
                    materialized.set(readSet);
                    return new ContextMaterializationOutcome.Ready(slices());
                });

        ContextAssemblyOutcome.Ready ready = assertInstanceOf(
                ContextAssemblyOutcome.Ready.class, assembler.assemble(attempt, command));

        assertEquals(winner, ready.readSet());
        assertEquals(winner, materialized.get());
        assertEquals(2, loads.get());
        assertFalse(candidate.equals(materialized.get()));
    }

    @Test
    void revokedExactMaterializationFailsClosedBeforeRouter() {
        UserTurnCommand command = command("draw a flow");
        FencedAttempt attempt = attempt(command, 17);
        DefaultBaseTurnContextAssembler assembler = new DefaultBaseTurnContextAssembler(
                ignored -> new ContextReadSetLoadOutcome.Found(readSet(17, "profile-v1")),
                (ignored, ignoredProposal) -> new ContextReadSetOutcome.Retry(),
                (ignored, ignoredCommand) -> new ContextCandidateLoadOutcome.Retry(),
                (ignored, ignoredCommand, ignoredReadSet) ->
                        new ContextMaterializationOutcome.Revoked("PROFILE_REVOKED"));

        ContextAssemblyOutcome.Terminal terminal = assertInstanceOf(
                ContextAssemblyOutcome.Terminal.class, assembler.assemble(attempt, command));

        assertEquals("CONTEXT_READ_SET_REVOKED", terminal.code());
        assertTrue(terminal.reason().contains("REVOKED"));
    }

    private static UserTurnCommand command(String content) {
        return new UserTurnCommand(
                "turn-1", "conversation-1", "diagram-1", "client-1", content, "runtime-1",
                TurnDeclarations.empty());
    }

    private static FencedAttempt attempt(UserTurnCommand command, long highWater) {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", command.turnId()),
                new AttemptLease("attempt-1", 1, Instant.parse("2026-07-26T00:01:00Z"), 30_000),
                highWater,
                TurnInputBindingDigestCalculator.current(command),
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy-hash"));
    }

    private static ContextReadSet readSet(long highWater, String profileReference) {
        return ContextReadSet.create(
                1,
                highWater,
                ContextSlicePin.pinned(ContextSlice.SUMMARY, "summary-v1", 1, digest('a')),
                ContextSlicePin.pinned(ContextSlice.MEMBERSHIP, "membership-v1", 1, digest('b')),
                ContextSlicePin.pinned(ContextSlice.PROFILE, profileReference, 1, digest('c')),
                ContextSlicePin.absent(ContextSlice.MEMORY, "NO_CONFIRMED_MEMORY"));
    }

    private static ContextMaterializedSlices slices() {
        return new ContextMaterializedSlices(
                new AvailableContext<>(new CurrentMessageAttachmentsContext(digest('d'), List.of()), "attachment-bindings"),
                new AvailableContext<>(new ActiveClarificationContext(java.util.Set.of()), "clarification-row"),
                new AvailableContext<>(new TrustedCanvasContext(false, 0, 0, ""), "canvas-state"),
                new AvailableContext<>(new ValidatedSelectionContext(false, 0), "selection-validation"),
                new AvailableContext<>(new ConversationContext(List.of(), ""), "conversation-v1"),
                new AbsentContext<>("STANDALONE_DIAGRAM"),
                new AbsentContext<>("NO_CHARTBOOK_PROFILE"),
                new AbsentContext<>("NO_CONFIRMED_MEMORY"),
                new ContextDiagnostics(List.of()));
    }

    private static String digest(char value) {
        return String.valueOf(value).repeat(64);
    }
}
