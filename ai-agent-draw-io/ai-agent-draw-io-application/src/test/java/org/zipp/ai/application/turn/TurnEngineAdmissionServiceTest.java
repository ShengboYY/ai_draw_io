package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TurnEngineAdmissionServiceTest {

    @Test
    void admissionForwardsCanonicalKeyAndMigrationSnapshotToStickyPort() {
        AuthenticatedActor actor = new AuthenticatedActor("owner-1", "cohort-1");
        ConversationRef conversation = new ConversationRef(
                "conversation-1", "owner-1", "diagram-1", ConversationStatus.ACTIVE);
        UserTurnCommand command = new UserTurnCommand(
                "turn-1", "legacy-session-1", "diagram-1", "client-1", "draw a box", null,
                TurnDeclarations.empty());
        MigrationStateSnapshot migration = new MigrationStateSnapshot(
                7, TurnEngineMode.V2_CANARY, Instant.parse("2026-07-26T00:00:00Z"));
        ExecutionPolicySnapshot policy = new ExecutionPolicySnapshot(
                1, TurnEngineMode.V2_CANARY, "{\"sourcePlan\":\"OFF\"}", "policy-hash");
        VersionedRequestFingerprintSet fingerprints = new VersionedRequestFingerprintSet(
                java.util.List.of(new VersionedRequestFingerprint(1, "fingerprint-hash")));
        AtomicReference<TurnEngineAssignmentCommand> captured = new AtomicReference<>();
        TurnKey expectedKey = new TurnKey("owner-1", "conversation-1", "turn-1");
        TurnEngineAssignment assignment = new TurnEngineAssignment(
                expectedKey,
                "diagram-1",
                fingerprints.current(),
                SelectedTurnEngine.V2,
                migration,
                policy,
                new NoMemoryWrite());

        TurnEngineAdmissionService service = new TurnEngineAdmissionService(
                () -> migration,
                input -> {
                    captured.set(input);
                    return new AdmissionWriteOutcome.Assigned(assignment);
                }, new OpenAdmissionBarrier(), cohort -> SelectedTurnEngine.V2);

        AdmissionWriteOutcome outcome = service.admit(actor, conversation, command, fingerprints, policy);

        assertInstanceOf(AdmissionWriteOutcome.Assigned.class, outcome);
        assertEquals(expectedKey, captured.get().key());
        assertEquals(7, captured.get().migration().generation());
        assertEquals(new NoMemoryWrite(), captured.get().memoryWrite());
        assertEquals(SelectedTurnEngine.V2, captured.get().selectedEngine());
    }

    @Test
    void closedAdmissionGateRejectsBeforeReadingMigrationOrWritingAssignment() {
        AuthenticatedActor actor = new AuthenticatedActor("owner-1", "cohort-1");
        ConversationRef conversation = new ConversationRef(
                "conversation-1", "owner-1", "diagram-1", ConversationStatus.ACTIVE);
        UserTurnCommand command = new UserTurnCommand(
                "turn-1", "conversation:conversation-1", "diagram-1", "client-1", "draw a box", null,
                TurnDeclarations.empty());
        boolean[] migrationRead = {false};
        boolean[] assignmentWrite = {false};

        TurnEngineAdmissionService service = new TurnEngineAdmissionService(
                () -> {
                    migrationRead[0] = true;
                    return new MigrationStateSnapshot(1, TurnEngineMode.V2_CANARY,
                            Instant.parse("2026-07-26T00:00:00Z"));
                },
                input -> {
                    assignmentWrite[0] = true;
                    throw new AssertionError("assignment must not be written while paused");
                },
                new ClosedAdmissionBarrier());

        AdmissionWriteOutcome outcome = service.admit(
                actor,
                conversation,
                command,
                new VersionedRequestFingerprintSet(
                        java.util.List.of(new VersionedRequestFingerprint(1, "fingerprint-hash"))),
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy-hash"));

        AdmissionWriteOutcome.Rejected rejected = assertInstanceOf(
                AdmissionWriteOutcome.Rejected.class, outcome);
        assertEquals("TURN_INSTANCE_NOT_READY", rejected.code());
        assertEquals(false, migrationRead[0]);
        assertEquals(false, assignmentWrite[0]);
    }

    @Test
    void retiredModeKeepsV2AdmissionLive() {
        AuthenticatedActor actor = new AuthenticatedActor("owner-1", "cohort-1");
        ConversationRef conversation = new ConversationRef(
                "conversation-1", "owner-1", "diagram-1", ConversationStatus.ACTIVE);
        UserTurnCommand command = new UserTurnCommand(
                "turn-1", "conversation:conversation-1", "diagram-1", "client-1", "draw a box", null,
                TurnDeclarations.empty());
        MigrationStateSnapshot migration = new MigrationStateSnapshot(
                8, TurnEngineMode.RETIRED, Instant.parse("2026-07-26T00:00:00Z"));
        SelectedTurnEngine[] selected = {null};

        TurnEngineAdmissionService service = new TurnEngineAdmissionService(
                () -> migration,
                input -> {
                    selected[0] = input.selectedEngine();
                    return new AdmissionWriteOutcome.Assigned(new TurnEngineAssignment(
                            input.key(), input.diagramId(), input.fingerprints().current(),
                            input.selectedEngine(), input.migration(), input.policy(), input.memoryWrite()));
                },
                new OpenAdmissionBarrier());

        AdmissionWriteOutcome outcome = service.admit(
                actor,
                conversation,
                command,
                new VersionedRequestFingerprintSet(
                        java.util.List.of(new VersionedRequestFingerprint(1, "fingerprint-hash"))),
                new ExecutionPolicySnapshot(1, TurnEngineMode.RETIRED, "{}", "policy-hash"));

        AdmissionWriteOutcome.Assigned assigned = assertInstanceOf(
                AdmissionWriteOutcome.Assigned.class, outcome);
        assertEquals(SelectedTurnEngine.V2, assigned.assignment().selectedEngine());
        assertEquals(SelectedTurnEngine.V2, selected[0]);
    }

    private static final class OpenAdmissionBarrier implements AdmissionBarrier {
        @Override
        public void pauseAndDrain() {
        }

        @Override
        public void resume() {
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    private static final class ClosedAdmissionBarrier implements AdmissionBarrier {
        @Override
        public void pauseAndDrain() {
        }

        @Override
        public void resume() {
        }

        @Override
        public boolean isOpen() {
            return false;
        }
    }
}
