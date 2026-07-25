package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TurnContractTest {

    @Test
    void turnKeyUsesCanonicalConversationAndRejectsCrossOwnerBinding() {
        AuthenticatedActor actor = new AuthenticatedActor("owner-1", "cohort-1");
        ConversationRef conversation = new ConversationRef(
                "conversation-1", "owner-1", "diagram-1", ConversationStatus.ACTIVE);

        assertEquals(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                TurnKey.of(actor, conversation, "turn-1"));
        assertThrows(IllegalArgumentException.class, () -> TurnKey.of(
                actor,
                new ConversationRef("conversation-2", "owner-2", "diagram-1", ConversationStatus.ACTIVE),
                "turn-1"));
    }

    @Test
    void declarationCollectionsAreImmutable() {
        TurnDeclarations declarations = new TurnDeclarations(
                List.of(new OpaqueConversationFileRef("file-1")),
                new NoClarificationReply(),
                List.of(new UntrustedLegacyVersionDeclaration("legacy-1")),
                new NoMemoryWrite());

        assertThrows(UnsupportedOperationException.class, () ->
                declarations.currentTurnAttachments().add(new OpaqueConversationFileRef("file-2")));
        assertThrows(UnsupportedOperationException.class, () ->
                declarations.legacySelectedSources().add(new UntrustedLegacyVersionDeclaration("legacy-2")));
    }

    @Test
    void turnStartRejectsDuplicateAttachmentRefsBeforePersistence() {
        TurnEngineAssignment assignment = new TurnEngineAssignment(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                "diagram-1",
                new VersionedRequestFingerprint(1, "fingerprint"),
                SelectedTurnEngine.V2,
                new MigrationStateSnapshot(1, TurnEngineMode.V2_CANARY,
                        Instant.parse("2026-07-26T00:00:00Z")),
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy"),
                new NoMemoryWrite());

        assertThrows(IllegalArgumentException.class, () -> new TurnStartCommand(
                assignment.key(),
                "diagram-1",
                assignment,
                "draw it",
                "client-1",
                List.of(new OpaqueConversationFileRef("file-1"), new OpaqueConversationFileRef("file-1")),
                "input"));
    }

    @Test
    void fingerprintIgnoresAliasesAndRuntimeSessionsButBindsAttachmentOrderAndMemory() {
        UserTurnCommand first = new UserTurnCommand(
                "turn-1", "legacy-session", "diagram-1", "client-1", "draw it", "runtime-1",
                new TurnDeclarations(
                        List.of(new OpaqueConversationFileRef("file-a"), new OpaqueConversationFileRef("file-b")),
                        new NoClarificationReply(),
                        List.of(),
                        new NoMemoryWrite()));
        UserTurnCommand aliasRetry = new UserTurnCommand(
                "turn-1", "canonical-conversation", "diagram-1", "client-1", "draw it", "runtime-2",
                first.declarations());
        UserTurnCommand reordered = new UserTurnCommand(
                "turn-1", "legacy-session", "diagram-1", "client-1", "draw it", "runtime-1",
                new TurnDeclarations(
                        List.of(new OpaqueConversationFileRef("file-b"), new OpaqueConversationFileRef("file-a")),
                        new NoClarificationReply(),
                        List.of(),
                        new NoMemoryWrite()));
        UserTurnCommand memoryChanged = new UserTurnCommand(
                "turn-1", "legacy-session", "diagram-1", "client-1", "draw it", "runtime-1",
                new TurnDeclarations(
                        first.declarations().currentTurnAttachments(),
                        new NoClarificationReply(),
                        List.of(),
                        new RememberDecisionDeclaration(
                                1,
                                new MemoryWriteRuleVersion("rule-1"),
                                new MatchedInstructionSpan("span-1"),
                                new MemoryWriteSemanticDigest("digest-1"))));

        assertEquals(
                TurnRequestFingerprintCalculator.current(first),
                TurnRequestFingerprintCalculator.current(aliasRetry));
        assertNotEquals(
                TurnRequestFingerprintCalculator.current(first).digest(),
                TurnRequestFingerprintCalculator.current(reordered).digest());
        assertNotEquals(
                TurnRequestFingerprintCalculator.current(first).digest(),
                TurnRequestFingerprintCalculator.current(memoryChanged).digest());
    }

    @Test
    void fencedAttemptExposesOnlyImmutableFenceValues() {
        FencedAttempt attempt = new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-1", 2, Instant.parse("2026-07-26T00:01:00Z"), 30_000),
                18,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.LEGACY, "{}", "policy-hash"));

        assertEquals("attempt-1", attempt.attemptId());
        assertEquals(2, attempt.attemptEpoch());
        assertEquals(18, attempt.contextMessageHighWater());
    }
}
