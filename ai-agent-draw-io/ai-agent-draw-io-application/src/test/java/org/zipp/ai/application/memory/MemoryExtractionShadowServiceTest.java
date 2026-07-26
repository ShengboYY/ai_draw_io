package org.zipp.ai.application.memory;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.TurnKey;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MemoryExtractionShadowServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final TurnKey TURN = new TurnKey("owner-1", "conversation-1", "turn-1");

    @Test
    void safeCandidatesAreObservedWithoutConfirmationAndDuplicatesAreCollapsed() {
        MemoryShadowReport report = service().observe(event(
                new MemoryShadowCandidate("candidate-1", "labels", "plain-generation",
                        "Prefer short labels"),
                new MemoryShadowCandidate("candidate-2", "labels", "plain-generation",
                        "  prefer   short labels ")));

        assertEquals(new MemoryShadowMetrics(1, 1, 0, 0, 0), report.metrics());
        assertEquals(MemoryShadowCandidateStatus.SHADOW_ACCEPTED, report.observations().get(0).status());
        assertEquals("Prefer short labels", report.observations().get(0).canonicalText());
        assertEquals(MemoryShadowCandidateStatus.DUPLICATE, report.observations().get(1).status());
        assertNull(report.observations().get(1).canonicalText());
    }

    @Test
    void conflictingValuesStayUnmergedAndDoNotExposeEitherPayload() {
        MemoryShadowReport report = service().observe(event(
                new MemoryShadowCandidate("candidate-1", "labels", "plain-generation",
                        "Prefer short labels"),
                new MemoryShadowCandidate("candidate-2", "labels", "plain-generation",
                        "Prefer long labels")));

        assertEquals(new MemoryShadowMetrics(0, 0, 2, 0, 0), report.metrics());
        assertEquals(MemoryShadowCandidateStatus.CONFLICT, report.observations().get(0).status());
        assertNull(report.observations().get(0).canonicalText());
        assertEquals(MemoryShadowCandidateStatus.CONFLICT, report.observations().get(1).status());
        assertNull(report.observations().get(1).canonicalText());
    }

    @Test
    void sensitiveAndProfileOwnedTextIsRejectedWithSafeCodesOnly() {
        String secret = "Remember api_key=do-not-leak";
        MemoryShadowReport report = service().observe(event(
                new MemoryShadowCandidate("secret", "labels", "plain-generation", secret),
                new MemoryShadowCandidate("profile", "labels", "plain-generation", "Use default style"),
                new MemoryShadowCandidate("external", "labels", "plain-generation", "See https://example.test")));

        assertEquals(new MemoryShadowMetrics(0, 0, 0, 3, 0), report.metrics());
        assertEquals("MEMORY_SECRET_FORBIDDEN", report.observations().get(0).reasonCode());
        assertEquals("MEMORY_PROFILE_FIELD_FORBIDDEN", report.observations().get(1).reasonCode());
        assertEquals("MEMORY_EXTERNAL_FACT_FORBIDDEN", report.observations().get(2).reasonCode());
        assertEquals(false, report.toString().contains(secret));
    }

    @Test
    void profileOwnedDecisionKeyCannotEnterShadowMemory() {
        MemoryShadowReport report = service().observe(event(
                new MemoryShadowCandidate("profile-key", "default-style", "plain-generation",
                        "Prefer compact labels")));

        assertEquals(new MemoryShadowMetrics(0, 0, 0, 1, 0), report.metrics());
        assertEquals("MEMORY_PROFILE_FIELD_FORBIDDEN", report.observations().get(0).reasonCode());
    }

    @Test
    void staleCommittedEventIsRejectedBeforeCandidateInspection() {
        MemoryShadowReport report = service().observe(new MemoryShadowTurnCommitted(
                TURN, "chartbook-1", NOW.minus(Duration.ofDays(2)), List.of(
                new MemoryShadowCandidate("candidate-1", "labels", "plain-generation",
                        "Prefer short labels"))));

        assertEquals(new MemoryShadowMetrics(0, 0, 0, 1, 1), report.metrics());
        assertEquals("MEMORY_SHADOW_STALE_EVENT", report.observations().get(0).reasonCode());
    }

    private static MemoryExtractionShadowService service() {
        return new MemoryExtractionShadowService(
                new MemoryPolicySanitizer(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24));
    }

    private static MemoryShadowTurnCommitted event(MemoryShadowCandidate... candidates) {
        return new MemoryShadowTurnCommitted(TURN, "chartbook-1", NOW, List.of(candidates));
    }
}
