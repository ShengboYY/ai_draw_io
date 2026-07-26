package org.zipp.ai.application.turn.planning;

import java.util.List;

/** Role-tagged Probe facts prevent Direct and Retrieval candidates from being interchanged. */
public sealed interface RoleAvailability
        permits RoleAvailability.DirectAvailable,
        RoleAvailability.RetrievalAvailable,
        RoleAvailability.Unavailable {

    SourceRole role();

    record DirectAvailable(List<DirectCandidateFact> candidates) implements RoleAvailability {
        public DirectAvailable {
            if (candidates == null || candidates.isEmpty()
                    || candidates.stream().anyMatch(value -> value == null)) {
                throw new IllegalArgumentException("Direct candidates must not be empty");
            }
            candidates = List.copyOf(candidates);
        }

        @Override
        public SourceRole role() {
            return SourceRole.DIRECT;
        }
    }

    record RetrievalAvailable(List<RetrievalCandidateFact> candidates) implements RoleAvailability {
        public RetrievalAvailable {
            if (candidates == null || candidates.isEmpty()
                    || candidates.stream().anyMatch(value -> value == null)) {
                throw new IllegalArgumentException("Retrieval candidates must not be empty");
            }
            candidates = List.copyOf(candidates);
        }

        @Override
        public SourceRole role() {
            return SourceRole.RETRIEVAL;
        }
    }

    record Unavailable(
            SourceRole role,
            RoleUnavailability reason
    ) implements RoleAvailability {
        public Unavailable {
            if (role == null || reason == null) {
                throw new IllegalArgumentException("role unavailable values must not be null");
            }
        }
    }
}
