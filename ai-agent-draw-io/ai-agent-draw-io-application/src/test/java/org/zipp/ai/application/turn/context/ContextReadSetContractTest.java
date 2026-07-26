package org.zipp.ai.application.turn.context;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.MigrationStateSnapshot;
import org.zipp.ai.application.turn.SelectedTurnEngine;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.checkpoint.DefaultTurnDecisionCheckpointCoordinator;
import org.zipp.ai.application.turn.checkpoint.ProposedTurnDecisionCheckpoint;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpoint;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointLoadOutcome;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointOutcome;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointQueryPort;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextReadSetContractTest {

    @Test
    void readSetDigestIncludesEveryPinnedSliceAndHighWater() {
        ContextReadSet first = readSet(17, "profile-v1", 1);
        ContextReadSet changed = readSet(18, "profile-v1", 1);

        assertEquals(64, first.digest().length());
        assertTrue(!first.digest().equals(changed.digest()));
        assertEquals(17, first.messageHighWater());
        assertEquals("profile-v1", first.profile().reference());
    }

    @Test
    void readSetDigestChangesWhenConfirmedMemoryPinChanges() {
        ContextReadSet absent = readSet(17, "profile-v1", 1);
        ContextReadSet pinned = ContextReadSet.create(
                1,
                17,
                absent.summary(),
                absent.membership(),
                absent.profile(),
                ContextSlicePin.pinned(ContextSlice.MEMORY, "memory-v2", 2, digest('d')));

        assertNotEquals(absent.digest(), pinned.digest());
        assertEquals("memory-v2", pinned.memory().reference());
        assertEquals(2, pinned.memory().version());
    }

    @Test
    void readSetCoordinatorLoadsWinnerWithoutCallingCommit() {
        FencedAttempt attempt = attempt(17, "input-digest");
        ContextReadSet winner = readSet(17, "profile-v1", 1);
        AtomicBoolean commitCalled = new AtomicBoolean();
        DefaultContextReadSetCoordinator coordinator = new DefaultContextReadSetCoordinator(
                ignored -> new ContextReadSetLoadOutcome.Found(winner),
                (ignored, proposal) -> {
                    commitCalled.set(true);
                    return new ContextReadSetOutcome.Retry();
                });

        ContextReadSetOutcome outcome = coordinator.prepare(
                attempt, new ProposedContextReadSet(readSet(17, "profile-v2", 2)));

        ContextReadSetOutcome.Pinned pinned = assertInstanceOf(ContextReadSetOutcome.Pinned.class, outcome);
        assertEquals(winner, pinned.value());
        assertTrue(!commitCalled.get());
    }

    @Test
    void readSetCoordinatorPinsOnlyAfterMissing() {
        FencedAttempt attempt = attempt(17, "input-digest");
        ContextReadSet candidate = readSet(17, "profile-v1", 1);
        AtomicBoolean commitCalled = new AtomicBoolean();
        DefaultContextReadSetCoordinator coordinator = new DefaultContextReadSetCoordinator(
                ignored -> new ContextReadSetLoadOutcome.Missing(),
                (ignored, proposal) -> {
                    commitCalled.set(true);
                    return new ContextReadSetOutcome.Pinned(proposal.value());
                });

        ContextReadSetOutcome outcome = coordinator.prepare(
                attempt, new ProposedContextReadSet(candidate));

        assertEquals(candidate, assertInstanceOf(ContextReadSetOutcome.Pinned.class, outcome).value());
        assertTrue(commitCalled.get());
    }

    @Test
    void checkpointCoordinatorRejectsInputBindingMismatchBeforeLoading() {
        FencedAttempt attempt = attempt(17, "input-digest");
        AtomicBoolean queryCalled = new AtomicBoolean();
        DefaultTurnDecisionCheckpointCoordinator coordinator = new DefaultTurnDecisionCheckpointCoordinator(
                ignored -> {
                    queryCalled.set(true);
                    return new TurnDecisionCheckpointLoadOutcome.Missing();
                },
                (ignored, proposal) -> new TurnDecisionCheckpointOutcome.Retry());
        TurnDecisionCheckpoint candidate = TurnDecisionCheckpoint.create(
                1, digest('a'), digest('b'), "CLASSIFICATION", "{}");

        TurnDecisionCheckpointOutcome outcome = coordinator.prepare(
                attempt, new ProposedTurnDecisionCheckpoint(candidate));

        TurnDecisionCheckpointOutcome.Unavailable unavailable =
                assertInstanceOf(TurnDecisionCheckpointOutcome.Unavailable.class, outcome);
        assertEquals("STALE_ATTEMPT", unavailable.code().name());
        assertTrue(!queryCalled.get());
    }

    private static ContextReadSet readSet(long highWater, String profileReference, long profileVersion) {
        return ContextReadSet.create(
                1,
                highWater,
                ContextSlicePin.pinned(ContextSlice.SUMMARY, "summary-v1", 1, digest('a')),
                ContextSlicePin.pinned(ContextSlice.MEMBERSHIP, "membership-v1", 1, digest('b')),
                ContextSlicePin.pinned(ContextSlice.PROFILE, profileReference, profileVersion, digest('c')),
                ContextSlicePin.absent(ContextSlice.MEMORY, "NO_CONFIRMED_MEMORY"));
    }

    private static FencedAttempt attempt(long highWater, String inputDigest) {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-1", 1, Instant.parse("2026-07-26T00:01:00Z"), 30_000),
                highWater,
                inputDigest,
                new ExecutionPolicySnapshot(
                        1,
                        TurnEngineMode.V2_CANARY,
                        "{}",
                        "policy-hash"));
    }

    private static String digest(char value) {
        return String.valueOf(value).repeat(64);
    }
}
