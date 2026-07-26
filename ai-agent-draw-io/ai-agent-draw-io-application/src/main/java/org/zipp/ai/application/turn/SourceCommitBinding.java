package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.planning.SourcePlanIdentity;

/** Immutable source identity that freeze pins and every source-aware commit must echo. */
public record SourceCommitBinding(
        SourcePlanIdentity planIdentity,
        String sourceSnapshotRef,
        String snapshotBindingDigest,
        String executionEntryId
) {

    public SourceCommitBinding {
        if (planIdentity == null) {
            throw new IllegalArgumentException("planIdentity must not be null");
        }
        ContractValues.requiredText(sourceSnapshotRef, "sourceSnapshotRef");
        digest(snapshotBindingDigest, "snapshotBindingDigest");
        digest(executionEntryId, "executionEntryId");
    }

    private static void digest(String value, String name) {
        ContractValues.requiredText(value, name);
        if (value.length() != 64 || !value.chars().allMatch(character ->
                (character >= '0' && character <= '9')
                        || (character >= 'a' && character <= 'f'))) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
    }
}
