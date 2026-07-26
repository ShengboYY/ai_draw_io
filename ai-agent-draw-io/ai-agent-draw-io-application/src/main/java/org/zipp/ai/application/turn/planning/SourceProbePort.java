package org.zipp.ai.application.turn.planning;

/** Metadata/relevance-only source boundary; implementations must not return source contents. */
@FunctionalInterface
public interface SourceProbePort {

    SourceProbeOutcome probe(SourceProbeCommand command);

    /**
     * Production adapters need the server-owned scope identity to resolve the opaque command.
     * Test doubles can keep implementing the original command-only seam.
     */
    default SourceProbeOutcome probe(SourceProbeCommand command, SourceProbeContext context) {
        return probe(command);
    }
}
