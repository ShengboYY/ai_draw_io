package org.zipp.ai.application.turn.demand;

// Closed decision algebra prevents an untyped source mode from leaking downstream.

public sealed interface SourceDemandDecision
        permits NoSourceDemand, AcceptedSourceDemand, AmbiguousSourceDemand {
}
