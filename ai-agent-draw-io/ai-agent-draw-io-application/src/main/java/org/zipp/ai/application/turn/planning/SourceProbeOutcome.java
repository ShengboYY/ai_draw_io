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
            List<String> candidateRefs
    ) implements SourceProbeOutcome {
        public Available {
            if (binding == null || candidateRefs == null || candidateRefs.isEmpty()
                    || candidateRefs.stream().anyMatch(value -> value == null || value.isBlank())
                    || candidateRefs.stream().distinct().count() != candidateRefs.size()) {
                throw new IllegalArgumentException("Probe candidates must be unique and non-empty");
            }
            candidateRefs = List.copyOf(candidateRefs);
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
