package org.zipp.ai.application.memory;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class AutoMemoryManagementServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");
    private static final AutoMemoryFence FENCE = new AutoMemoryFence(
            AutoMemoryScope.user("owner-1"), "memory-1", 2);

    @Test
    void editRejectsSensitiveContentBeforeCallingPersistence() {
        RecordingStore store = new RecordingStore();
        AutoMemoryManagementService service = service(store);

        AutoMemoryManagementOutcome.Rejected rejected = assertInstanceOf(
                AutoMemoryManagementOutcome.Rejected.class,
                service.edit(FENCE, "remember password = secret-value"));

        assertEquals("MEMORY_SECRET_FORBIDDEN", rejected.code());
        assertNull(store.editedText);
    }

    @Test
    void editNormalizesTextAndCarriesTheVersionFence() {
        RecordingStore store = new RecordingStore();
        AutoMemoryManagementService service = service(store);

        assertInstanceOf(
                AutoMemoryManagementOutcome.Gone.class,
                service.edit(FENCE, "  Prefer   concise labels  "));

        assertEquals(FENCE, store.editedFence);
        assertEquals("Prefer concise labels", store.editedText);
        assertEquals(AutoMemoryObservationService.POLICY_VERSION, store.policyVersion);
        assertEquals(NOW, store.editedAt);
    }

    private static AutoMemoryManagementService service(RecordingStore store) {
        return new AutoMemoryManagementService(
                store,
                new MemoryPolicySanitizer(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class RecordingStore implements AutoMemoryManagementStorePort {
        private AutoMemoryFence editedFence;
        private String editedText;
        private String policyVersion;
        private Instant editedAt;

        @Override
        public List<AutoMemory> list(
                AutoMemoryScope scope, boolean includeObserved, boolean includeDisabled) {
            return List.of();
        }

        @Override
        public AutoMemoryManagementOutcome edit(
                AutoMemoryFence fence, String canonicalText, String policyVersion, Instant now) {
            this.editedFence = fence;
            this.editedText = canonicalText;
            this.policyVersion = policyVersion;
            this.editedAt = now;
            return new AutoMemoryManagementOutcome.Gone("TEST_OUTCOME");
        }

        @Override
        public AutoMemoryManagementOutcome disable(AutoMemoryFence fence, Instant now) {
            return new AutoMemoryManagementOutcome.Gone("TEST_OUTCOME");
        }

        @Override
        public AutoMemoryManagementOutcome activate(AutoMemoryFence fence, Instant now) {
            return new AutoMemoryManagementOutcome.Gone("TEST_OUTCOME");
        }

        @Override
        public AutoMemoryManagementOutcome delete(AutoMemoryFence fence) {
            return new AutoMemoryManagementOutcome.Gone("TEST_OUTCOME");
        }
    }
}
