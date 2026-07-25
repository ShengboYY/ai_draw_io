package org.zipp.ai.application.turn.demand;

// Implementations must preserve this restricted input boundary.

/** Restricted-input model boundary; it cannot receive source bodies or availability facts. */
public interface SourceDemandInterpreterPort {

    SourceDemandProposalOutcome interpret(RestrictedSourceDemandInput input);
}
