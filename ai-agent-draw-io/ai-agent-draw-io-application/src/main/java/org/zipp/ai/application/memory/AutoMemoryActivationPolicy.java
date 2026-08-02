package org.zipp.ai.application.memory;

/** Pure status policy shared by application tests and transactional persistence adapters. */
public final class AutoMemoryActivationPolicy {
    public static final int DEFAULT_INFERRED_EVIDENCE_THRESHOLD = 2;

    private final int inferredEvidenceThreshold;

    public AutoMemoryActivationPolicy() {
        this(DEFAULT_INFERRED_EVIDENCE_THRESHOLD);
    }

    public AutoMemoryActivationPolicy(int inferredEvidenceThreshold) {
        if (inferredEvidenceThreshold < 2) {
            throw new IllegalArgumentException("inferredEvidenceThreshold must be at least 2");
        }
        this.inferredEvidenceThreshold = inferredEvidenceThreshold;
    }

    public AutoMemoryStatus initialStatus(boolean explicit) {
        return explicit ? AutoMemoryStatus.ACTIVE : AutoMemoryStatus.OBSERVED;
    }

    public AutoMemoryStatus afterSupportingEvidence(
            AutoMemoryStatus current,
            boolean explicit,
            int distinctEvidenceCount
    ) {
        if (current == null || distinctEvidenceCount < 1) {
            throw new IllegalArgumentException("current status and evidence are required");
        }
        if (current == AutoMemoryStatus.DISABLED || current == AutoMemoryStatus.DELETED) {
            return current;
        }
        if (current == AutoMemoryStatus.ACTIVE || explicit
                || distinctEvidenceCount >= inferredEvidenceThreshold) {
            return AutoMemoryStatus.ACTIVE;
        }
        return AutoMemoryStatus.OBSERVED;
    }

    public boolean shouldPromoteInferredChallenger(
            boolean currentExplicit,
            int distinctConflictEvidenceCount
    ) {
        if (distinctConflictEvidenceCount < 1) {
            throw new IllegalArgumentException("conflicting evidence count must be positive");
        }
        return !currentExplicit && distinctConflictEvidenceCount >= inferredEvidenceThreshold;
    }

    public int inferredEvidenceThreshold() {
        return inferredEvidenceThreshold;
    }
}
