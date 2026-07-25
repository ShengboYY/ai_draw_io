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
                });

        AdmissionWriteOutcome outcome = service.admit(actor, conversation, command, fingerprints, policy);

        assertInstanceOf(AdmissionWriteOutcome.Assigned.class, outcome);
        assertEquals(expectedKey, captured.get().key());
        assertEquals(7, captured.get().migration().generation());
        assertEquals(new NoMemoryWrite(), captured.get().memoryWrite());
    }
}
