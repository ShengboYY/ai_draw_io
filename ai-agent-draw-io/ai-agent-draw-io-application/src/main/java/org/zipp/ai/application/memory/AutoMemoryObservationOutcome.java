package org.zipp.ai.application.memory;

/** Safe result of filtering and consolidating one automatic Memory observation. */
public sealed interface AutoMemoryObservationOutcome
        permits AutoMemoryObservationOutcome.Applied, AutoMemoryObservationOutcome.Conflict,
        AutoMemoryObservationOutcome.Suppressed, AutoMemoryObservationOutcome.Rejected {

    record Applied(AutoMemory memory, boolean evidenceAdded, boolean activated)
            implements AutoMemoryObservationOutcome {
        public Applied {
            if (memory == null) {
                throw new IllegalArgumentException("memory must not be null");
            }
        }
    }

    /** One unresolved model inference never overwrites a different canonical value. */
    record Conflict(AutoMemory current) implements AutoMemoryObservationOutcome {
        public Conflict {
            if (current == null) {
                throw new IllegalArgumentException("current must not be null");
            }
        }
    }

    /** Disabled and deleted items remain under user control. */
    record Suppressed(AutoMemory current) implements AutoMemoryObservationOutcome {
        public Suppressed {
            if (current == null) {
                throw new IllegalArgumentException("current must not be null");
            }
        }
    }

    record Rejected(String code) implements AutoMemoryObservationOutcome {
        public Rejected {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("code must not be blank");
            }
        }
    }
}
