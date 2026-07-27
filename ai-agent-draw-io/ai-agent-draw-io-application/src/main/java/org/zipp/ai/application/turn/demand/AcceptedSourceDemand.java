package org.zipp.ai.application.turn.demand;

// Resolved demand remains a typed declaration until a later authorized Probe stage.

import java.util.List;

public record AcceptedSourceDemand(
        SourceDemandKind kind,
        List<String> attachmentRefs,
        String relevanceQuery
) implements SourceDemandDecision {

    public AcceptedSourceDemand {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        attachmentRefs = List.copyOf(attachmentRefs == null ? List.of() : attachmentRefs);
        relevanceQuery = relevanceQuery == null ? null : relevanceQuery.trim();
    }
}
