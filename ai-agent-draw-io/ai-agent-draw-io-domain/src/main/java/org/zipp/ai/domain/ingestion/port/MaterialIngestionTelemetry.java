package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;

import java.time.Duration;

/** Worker stage telemetry boundary; implementations must not throw into processing. */
public interface MaterialIngestionTelemetry {
    MaterialIngestionTelemetry NOOP = (stage, result, duration) -> { };
    void record(ProcessingJobStage stage, String result, Duration duration);
}
