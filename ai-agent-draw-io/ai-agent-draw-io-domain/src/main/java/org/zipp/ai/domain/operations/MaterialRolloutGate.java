package org.zipp.ai.domain.operations;

import java.util.Objects;

/** Enforces the approved rollout order independently from individual Spring feature switches. */
public final class MaterialRolloutGate {
    private final MaterialFeatureSet features;
    private final MaterialReleaseApproval approval;

    public MaterialRolloutGate(MaterialFeatureSet features, MaterialReleaseApproval approval) {
        this.features = Objects.requireNonNull(features, "features");
        this.approval = Objects.requireNonNull(approval, "approval");
    }

    public boolean anonymousUploadAllowed() {
        return approval.releasable() && features.ingestionEnabled() && features.uploadEnabled()
                && features.libraryEnabled() && features.lifecycleEnabled() && features.retrievalEnabled()
                && features.citationCommitEnabled() && features.evidenceAnswerEnabled()
                && features.anonymousUploadEnabled();
    }
}
