package org.zipp.ai.application.turn;

/** Reads the durable safety conditions required before the legacy executor can be removed. */
public interface LegacyRetirementGatePort {

    LegacyRetirementReadiness readiness();

    static LegacyRetirementGatePort unavailable() {
        return () -> new LegacyRetirementReadiness(
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    record LegacyRetirementReadiness(
            long executableAssignments,
            long retryHorizonPending,
            long missingTombstones,
            long expiredTombstones
    ) {

        public LegacyRetirementReadiness {
            if (executableAssignments < 0 || retryHorizonPending < 0
                    || missingTombstones < 0 || expiredTombstones < 0) {
                throw new IllegalArgumentException("retirement readiness counts must not be negative");
            }
        }

        public boolean safeToRetire() {
            return executableAssignments == 0
                    && retryHorizonPending == 0
                    && missingTombstones == 0
                    && expiredTombstones == 0;
        }
    }
}
