package org.zipp.ai.application.turn.planning;

/** Only Optional Composite can carry a signed Direct-only execution entry. */
public sealed interface SourceExecutionEntry
        permits SourceExecutionEntry.Primary, SourceExecutionEntry.SignedDirectOnly {

    final class Primary implements SourceExecutionEntry {
        Primary() {
        }
    }

    final class SignedDirectOnly implements SourceExecutionEntry {

        private final String branchId;
        private final FallbackReason reason;

        SignedDirectOnly(String branchId, FallbackReason reason) {
            PlanningContractValues.digest(branchId, "direct-only entry branch id");
            this.branchId = branchId;
            this.reason = java.util.Objects.requireNonNull(reason, "reason");
        }

        public String branchId() {
            return branchId;
        }

        public FallbackReason reason() {
            return reason;
        }
    }
}
