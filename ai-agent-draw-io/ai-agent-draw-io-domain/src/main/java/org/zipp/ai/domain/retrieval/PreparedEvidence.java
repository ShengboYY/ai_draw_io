package org.zipp.ai.domain.retrieval;

import java.util.Objects;

public final class PreparedEvidence implements AutoCloseable {
    private final EvidenceBundle bundle;
    private final RunResourceDomain resources;

    public PreparedEvidence(EvidenceBundle bundle, RunResourceDomain resources) {
        this.bundle = Objects.requireNonNull(bundle, "bundle");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public EvidenceBundle bundle() {
        return bundle;
    }

    @Override
    public void close() {
        resources.closeExactlyOnce(CloseReason.COMPLETED);
    }
}
