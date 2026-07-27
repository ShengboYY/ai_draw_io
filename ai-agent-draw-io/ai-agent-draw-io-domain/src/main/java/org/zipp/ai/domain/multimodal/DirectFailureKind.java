package org.zipp.ai.domain.multimodal;

/** Stable failure category used for retry and user-facing attribution decisions. */
public enum DirectFailureKind {
    VISUAL_PROVIDER(true),
    PROJECTION(true),
    DEPENDENCY(false),
    COMMIT(false),
    UNKNOWN(false);

    private final boolean retryable;

    DirectFailureKind(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
