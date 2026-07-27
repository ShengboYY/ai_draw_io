package org.zipp.ai.domain.operations;

/** Content-free capability projection boundary used by the scheduled monitor. */
public interface MaterialCapabilityTelemetry {
    void publish(MaterialCapabilityReport report);
}
