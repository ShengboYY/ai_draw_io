package org.zipp.ai.domain.retrieval;

import java.util.List;

/** Server-resolved canvas semantics carried from target resolution into answer generation. */
public record EvidenceTarget(String cellId, String kind, String label,
                             String sourceId, String targetId, List<String> nearbyLabels) {
    public EvidenceTarget {
        nearbyLabels = List.copyOf(nearbyLabels == null ? List.of() : nearbyLabels);
    }

    public EvidenceTarget(String cellId, String kind, String label) {
        this(cellId, kind, label, "", "", List.of());
    }
}
