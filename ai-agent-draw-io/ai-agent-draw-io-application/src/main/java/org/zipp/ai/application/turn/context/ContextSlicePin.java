package org.zipp.ai.application.turn.context;

import java.util.Objects;

/** Immutable reference to one versioned context slice; it never contains source content. */
public record ContextSlicePin(
        ContextSlice slice,
        ContextPinState state,
        String reference,
        long version,
        String contentDigest
) {

    public ContextSlicePin {
        Objects.requireNonNull(slice, "slice");
        Objects.requireNonNull(state, "state");
        if (reference == null || reference.isBlank() || reference.length() > 256) {
            throw new IllegalArgumentException("context pin reference must be bounded");
        }
        if (version < 0) {
            throw new IllegalArgumentException("context pin version must not be negative");
        }
        contentDigest = contentDigest == null ? "" : contentDigest.trim();
        if (state == ContextPinState.PINNED && !hexDigest(contentDigest)) {
            throw new IllegalArgumentException("pinned context slices require a SHA-256 digest");
        }
        if (state != ContextPinState.PINNED && (version != 0 || !contentDigest.isEmpty())) {
            throw new IllegalArgumentException("unmaterialized context slices cannot carry a version");
        }
    }

    public static ContextSlicePin pinned(
            ContextSlice slice,
            String reference,
            long version,
            String contentDigest
    ) {
        return new ContextSlicePin(slice, ContextPinState.PINNED, reference, version, contentDigest);
    }

    public static ContextSlicePin absent(ContextSlice slice, String reason) {
        return new ContextSlicePin(slice, ContextPinState.ABSENT, reason, 0, "");
    }

    public static ContextSlicePin degraded(ContextSlice slice, String diagnostic) {
        return new ContextSlicePin(slice, ContextPinState.DEGRADED, diagnostic, 0, "");
    }

    public static ContextSlicePin revoked(ContextSlice slice, String reason) {
        return new ContextSlicePin(slice, ContextPinState.REVOKED, reason, 0, "");
    }

    private static boolean hexDigest(String value) {
        return value.length() == 64 && value.chars().allMatch(character ->
                (character >= '0' && character <= '9')
                        || (character >= 'a' && character <= 'f'));
    }
}
