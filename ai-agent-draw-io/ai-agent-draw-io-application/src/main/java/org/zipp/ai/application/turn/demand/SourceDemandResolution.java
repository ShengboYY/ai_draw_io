package org.zipp.ai.application.turn.demand;

// Resolution remains separate from authorization, snapshot, and evidence preparation.

public sealed interface SourceDemandResolution
        permits ResolvedSourceDemand, NeedsSourceClarification, SourceDemandUnavailable {
}
