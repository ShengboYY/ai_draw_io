package org.zipp.ai.application.turn.demand;

// Plain is an explicit resolved decision, not an absence of a source service call.

public record NoSourceDemand() implements SourceDemandDecision {
}
