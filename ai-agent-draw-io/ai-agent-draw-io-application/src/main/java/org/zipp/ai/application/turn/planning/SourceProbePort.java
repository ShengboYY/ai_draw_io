package org.zipp.ai.application.turn.planning;

/** Metadata/relevance-only source boundary; implementations must not return source contents. */
@FunctionalInterface
public interface SourceProbePort {

    SourceProbeOutcome probe(SourceProbeCommand command);
}
