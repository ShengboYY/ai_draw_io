package org.zipp.ai.domain.multimodal;

import java.util.Objects;

/** Bounded user resolution for one reason returned by a prior direct-image attempt. */
public record DirectClarification(String reasonCode, Resolution resolution,
                                  String observedValue) {
    public DirectClarification {
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        if (reasonCode.isBlank() || reasonCode.length() > 160
                || !reasonCode.matches("[A-Za-z0-9_:-]+")) {
            throw new IllegalArgumentException("valid direct clarification reasonCode is required");
        }
        resolution = Objects.requireNonNull(resolution, "resolution");
        observedValue = observedValue == null ? "" : observedValue.trim();
        if (observedValue.length() > 200) {
            throw new IllegalArgumentException("direct clarification observedValue is too long");
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
