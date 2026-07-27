package org.zipp.ai.application.turn;

import java.util.List;

/** Current fingerprint first, followed by bounded retry-horizon compatibility candidates. */
public record VersionedRequestFingerprintSet(List<VersionedRequestFingerprint> candidates) {

    public VersionedRequestFingerprintSet {
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("at least one fingerprint candidate is required");
        }
    }

    public VersionedRequestFingerprint current() {
        return candidates.get(0);
    }
}
