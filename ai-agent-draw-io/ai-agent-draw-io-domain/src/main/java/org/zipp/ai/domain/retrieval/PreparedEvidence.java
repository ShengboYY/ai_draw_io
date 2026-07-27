package org.zipp.ai.domain.retrieval;

import java.util.Objects;
import java.util.List;

public final class PreparedEvidence implements AutoCloseable {
    private final EvidenceBundle bundle;
    private final RunResourceDomain resources;
    private final List<EvidenceTarget> targets;

    public PreparedEvidence(EvidenceBundle bundle, RunResourceDomain resources) {
        this(bundle, resources, List.of());
    }

    public PreparedEvidence(EvidenceBundle bundle, RunResourceDomain resources, List<EvidenceTarget> targets) {
        this.bundle = Objects.requireNonNull(bundle, "bundle");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.targets = List.copyOf(targets == null ? List.of() : targets);
    }

    public EvidenceBundle bundle() {
        return bundle;
    }

    /** Domain commit services share this aggregate-owned lifecycle; callers cannot replace it. */
    public RunResourceDomain resources() {
        return resources;
    }

    public List<EvidenceTarget> targets() {
        return targets;
    }

    @Override
    public void close() {
        resources.closeExactlyOnce(CloseReason.COMPLETED);
    }
}
