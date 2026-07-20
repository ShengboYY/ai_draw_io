package org.zipp.ai.domain.retrieval;

import java.util.List;

public record EvidenceBundle(String bundleId, String requestId, String runId,
                             SourceMode effectiveSourceMode, List<EvidenceBundleItem> items) {
    public EvidenceBundle {
        items = List.copyOf(items == null ? List.of() : items);
    }
}
