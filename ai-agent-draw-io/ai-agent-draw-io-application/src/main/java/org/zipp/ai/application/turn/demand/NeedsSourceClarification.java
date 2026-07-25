package org.zipp.ai.application.turn.demand;

// Clarification is terminal/application state, not a fallback source read.

import java.util.List;

public record NeedsSourceClarification(
        String kind,
        List<DemandResolutionReason> reasons
) implements SourceDemandResolution {

    public NeedsSourceClarification {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
    }
}
