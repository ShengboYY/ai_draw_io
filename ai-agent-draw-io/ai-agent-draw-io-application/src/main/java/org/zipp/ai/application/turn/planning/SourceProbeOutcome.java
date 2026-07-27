package org.zipp.ai.application.turn.planning;

import java.util.List;

/** Factual Probe result. Policy decisions such as fallback remain in the planner. */
public sealed interface SourceProbeOutcome
        permits SourceProbeOutcome.Available,
        SourceProbeOutcome.Unavailable,
        SourceProbeOutcome.Terminal,
        SourceProbeOutcome.Cancelled {

    SourceProbeBinding binding();

    record Available(
            SourceProbeBinding binding,
            SourceAvailability availability
    ) implements SourceProbeOutcome {
        public Available {
            if (binding == null || availability == null) {
                throw new IllegalArgumentException("Probe availability values must not be null");
            }
        }

        /** Compatibility constructor for the Optional Retrieval slice. */
        public Available(SourceProbeBinding binding, List<String> candidateRefs) {
            this(binding, new SourceAvailability.SingleRole(
                    new RoleAvailability.RetrievalAvailable(candidateRefs.stream()
                            .map(value -> new RetrievalCandidateFact(binding, value))
                            .toList())));
        }
    }

    record Unavailable(
            SourceProbeBinding binding,
            Unavailability reason
    ) implements SourceProbeOutcome {
        public Unavailable {
            if (binding == null || reason == null) {
                throw new IllegalArgumentException("Probe unavailable values must not be null");
            }
        }
    }

    record Terminal(
            SourceProbeBinding binding,
            String reason
    ) implements SourceProbeOutcome {
        public Terminal {
            if (binding == null || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Probe terminal values must not be blank");
            }
        }
    }

    record Cancelled(
            SourceProbeBinding binding,
            String reason
    ) implements SourceProbeOutcome {
        public Cancelled {
            if (binding == null || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Probe cancellation values must not be blank");
            }
        }
    }

    enum Unavailability {
        NO_MATCH,
        TIMEOUT,
        DEPENDENCY_UNAVAILABLE
    }
}
