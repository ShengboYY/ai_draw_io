package org.zipp.ai.application.memory;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.TurnKey;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryObservationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");
    private static final TurnKey TURN = new TurnKey("owner-1", "conversation-1", "turn-1");

    @Test
    void explicitObservationIsSanitizedAndImmediatelyActive() {
        RecordingStore store = new RecordingStore();
        AutoMemoryObservationOutcome.Applied applied = assertInstanceOf(
                AutoMemoryObservationOutcome.Applied.class,
                service(store).observe(command(
                        AutoMemoryScope.user("owner-1"),
                        "Label Density",
                        "Prefer concise labels",
                        MemoryObservationKind.EXPLICIT,
                        0.4d)));

        assertEquals(AutoMemoryStatus.ACTIVE, applied.memory().status());
        assertTrue(applied.memory().explicit());
        assertEquals(1.0d, store.observations.get(0).confidence());
        assertEquals("label-density", store.observations.get(0).semanticKey());
        assertEquals("Prefer concise labels", store.observations.get(0).canonicalText());
    }

    @Test
    void inferredObservationNeedsIndependentSupportingTurns() {
        AutoMemoryActivationPolicy policy = new AutoMemoryActivationPolicy();

        assertEquals(AutoMemoryStatus.OBSERVED,
                policy.initialStatus(false));
        assertEquals(AutoMemoryStatus.OBSERVED,
                policy.afterSupportingEvidence(AutoMemoryStatus.OBSERVED, false, 1));
        assertEquals(AutoMemoryStatus.ACTIVE,
                policy.afterSupportingEvidence(AutoMemoryStatus.OBSERVED, false, 2));
    }

    @Test
    void disabledMemoryCannotBeReactivatedByNewEvidence() {
        AutoMemoryActivationPolicy policy = new AutoMemoryActivationPolicy();

        assertEquals(AutoMemoryStatus.DISABLED,
                policy.afterSupportingEvidence(AutoMemoryStatus.DISABLED, true, 4));
        assertEquals(AutoMemoryStatus.DELETED,
                policy.afterSupportingEvidence(AutoMemoryStatus.DELETED, true, 4));
    }

    @Test
    void inferredChallengerNeedsTwoTurnsAndCannotReplaceExplicitContent() {
        AutoMemoryActivationPolicy policy = new AutoMemoryActivationPolicy();

        assertFalse(policy.shouldPromoteInferredChallenger(false, 1));
        assertTrue(policy.shouldPromoteInferredChallenger(false, 2));
        assertFalse(policy.shouldPromoteInferredChallenger(true, 4));
        assertThrows(IllegalArgumentException.class,
                () -> policy.shouldPromoteInferredChallenger(false, 0));
    }

    @Test
    void unsafeObservationIsRejectedBeforePersistence() {
        RecordingStore store = new RecordingStore();
        AutoMemoryObservationOutcome.Rejected rejected = assertInstanceOf(
                AutoMemoryObservationOutcome.Rejected.class,
                service(store).observe(command(
                        AutoMemoryScope.chartbook("owner-1", "chartbook-1"),
                        "credentials",
                        "Use api_key=do-not-store",
                        MemoryObservationKind.INFERRED,
                        0.9d)));

        assertEquals("MEMORY_SECRET_FORBIDDEN", rejected.code());
        assertTrue(store.observations.isEmpty());
    }

    @Test
    void userScopeCannotAddressAnotherOwner() {
        assertThrows(IllegalArgumentException.class,
                () -> new AutoMemoryScope("owner-1", MemoryScopeType.USER, "owner-2"));
    }

    private static AutoMemoryObservationService service(RecordingStore store) {
        return new AutoMemoryObservationService(
                new MemoryPolicySanitizer(),
                new AutoMemoryActivationPolicy(),
                store,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AutoMemoryObservationCommand command(
            AutoMemoryScope scope,
            String semanticKey,
            String text,
            MemoryObservationKind kind,
            double confidence
    ) {
        return new AutoMemoryObservationCommand(
                scope,
                AutoMemoryType.PREFERENCE,
                semanticKey,
                "Label preference",
                text,
                TURN,
                "diagram-1",
                kind,
                confidence);
    }

    /**
     * The fake applies the pure activation policy so the test checks the service/port contract
     * without reproducing SQL behavior.
     */
    private static final class RecordingStore implements AutoMemoryObservationStorePort {
        private final List<SanitizedAutoMemoryObservation> observations = new ArrayList<>();

        @Override
        public AutoMemoryObservationOutcome observe(
                SanitizedAutoMemoryObservation observation,
                AutoMemoryActivationPolicy activationPolicy
        ) {
            observations.add(observation);
            AutoMemoryStatus status = activationPolicy.initialStatus(observation.explicit());
            return new AutoMemoryObservationOutcome.Applied(new AutoMemory(
                    "memory-1",
                    observation.scope(),
                    observation.type(),
                    observation.semanticKey(),
                    observation.title(),
                    observation.canonicalText(),
                    status,
                    observation.confidence(),
                    1,
                    observation.explicit(),
                    1,
                    observation.observedAt(),
                    observation.observedAt()),
                    true,
                    status == AutoMemoryStatus.ACTIVE);
        }
    }
}
