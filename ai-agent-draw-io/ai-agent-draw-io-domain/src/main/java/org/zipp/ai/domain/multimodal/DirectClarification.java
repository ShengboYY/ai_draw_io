package org.zipp.ai.domain.multimodal;

import java.util.Objects;

/** Bounded user resolution for one reason returned by a prior direct-image attempt. */
public record DirectClarification(String reasonCode, Resolution resolution,
                                  String observedFingerprint) {
    public DirectClarification {
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        if (reasonCode.isBlank() || reasonCode.length() > 160
                || !reasonCode.matches("[A-Za-z0-9_:-]+")) {
            throw new IllegalArgumentException("valid direct clarification reasonCode is required");
        }
        resolution = Objects.requireNonNull(resolution, "resolution");
        observedFingerprint = observedFingerprint == null ? "" : observedFingerprint.trim();
        if (!observedFingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "valid direct clarification observedFingerprint is required");
        }
    }

    public enum Resolution {
        ACCEPT_OBSERVED,
        FORWARD,
        REVERSE,
        BIDIRECTIONAL,
        UNDIRECTED
    }
}
