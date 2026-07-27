package org.zipp.ai.application.turn.demand;

// Resolution records facts only; it never performs source I/O.

import java.util.List;

public record ResolvedSourceDemand(
        SourceDemandDecision decision,
        List<DemandResolutionReason> reasons
) implements SourceDemandResolution {

    public ResolvedSourceDemand {
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
    }
}
