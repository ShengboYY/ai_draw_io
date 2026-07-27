package org.zipp.ai.application.turn.planning;

/** Closed evidence result keeps fallback-eligible failures separate from terminal failures. */
public sealed interface OptionalEvidenceOutcome
        permits OptionalEvidenceOutcome.Ready,
        OptionalEvidenceOutcome.FallbackEligible,
        OptionalEvidenceOutcome.Terminal,
        OptionalEvidenceOutcome.Cancelled {

    record Ready(String evidenceRef) implements OptionalEvidenceOutcome {
        public Ready {
            if (evidenceRef == null || evidenceRef.isBlank()) {
                throw new IllegalArgumentException("evidence ref must not be blank");
            }
        }
    }

    record FallbackEligible(FallbackReason reason) implements OptionalEvidenceOutcome {
        public FallbackEligible {
            if (reason == null || reason == FallbackReason.SOURCE_NO_MATCH
                    || reason == FallbackReason.SOURCE_UNAVAILABLE) {
                throw new IllegalArgumentException("invalid post-Probe fallback reason");
            }
        }
    }

    record Terminal(String reason) implements OptionalEvidenceOutcome {
        public Terminal {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("terminal reason must not be blank");
            }
        }
    }

    record Cancelled(String reason) implements OptionalEvidenceOutcome {
        public Cancelled {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("cancel reason must not be blank");
            }
        }
    }
}
