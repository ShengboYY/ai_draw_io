package org.zipp.ai.application.turn;

public sealed interface AdmissionWriteOutcome
        permits AdmissionWriteOutcome.Assigned,
        AdmissionWriteOutcome.Reused,
        AdmissionWriteOutcome.LegacyRetryGone,
        AdmissionWriteOutcome.Rejected {

    record Assigned(TurnEngineAssignment assignment) implements AdmissionWriteOutcome {
    }

    record Reused(TurnEngineAssignment assignment) implements AdmissionWriteOutcome {
    }

    record LegacyRetryGone(TurnKey key, String reason) implements AdmissionWriteOutcome {

        public LegacyRetryGone {
            if (key == null) {
                throw new IllegalArgumentException("key must not be null");
            }
            ContractValues.requiredText(reason, "reason");
        }
    }

    record Rejected(TurnKey key, String code) implements AdmissionWriteOutcome {

        public Rejected {
            if (key == null) {
                throw new IllegalArgumentException("key must not be null");
            }
            ContractValues.requiredText(code, "code");
        }
    }
}
