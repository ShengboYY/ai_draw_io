package org.zipp.ai.application.turn;

public record VersionedRequestFingerprint(int schemaVersion, String digest) {

    public VersionedRequestFingerprint {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        ContractValues.requiredText(digest, "digest");
    }
}
