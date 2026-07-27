package org.zipp.ai.domain.retrieval.port;

import java.time.Duration;

/** Content-free, low-cardinality retrieval telemetry boundary. */
@FunctionalInterface
public interface MaterialRetrievalTelemetry {
    MaterialRetrievalTelemetry NOOP = (route, sourceMode, result, latency, evidenceItems) -> { };

    void record(String route, String sourceMode, String result, Duration latency, int evidenceItems);

    default void recordCandidates(String route, String lane, String stage, int candidates) { }
}
