package org.zipp.ai.domain.operations;

/** Operator pin proving which immutable release report approved the current rollout. */
public record MaterialReleaseApproval(boolean approved, String reportVersion) {
    public MaterialReleaseApproval {
        reportVersion = reportVersion == null ? "" : reportVersion.trim();
    }

    /** Invalid operator configuration blocks material rollout without preventing application startup. */
    public boolean releasable() {
        return approved && reportVersion.matches("rag-approval-v1:[0-9a-f]{64}");
    }
}
