package org.zipp.ai.application.turn.planning;

/** Single-role or role-aware Composite Probe facts. */
public sealed interface SourceAvailability
        permits SourceAvailability.SingleRole,
        SourceAvailability.Composite {

    record SingleRole(RoleAvailability role) implements SourceAvailability {
        public SingleRole {
            if (role == null) {
                throw new IllegalArgumentException("single role availability must not be null");
            }
        }
    }

    record Composite(
            RoleAvailability direct,
            RoleAvailability retrieval
    ) implements SourceAvailability {
        public Composite {
            if (direct == null || retrieval == null
                    || direct.role() != SourceRole.DIRECT
                    || retrieval.role() != SourceRole.RETRIEVAL) {
                throw new IllegalArgumentException("Composite availability roles are invalid");
            }
        }
    }
}
