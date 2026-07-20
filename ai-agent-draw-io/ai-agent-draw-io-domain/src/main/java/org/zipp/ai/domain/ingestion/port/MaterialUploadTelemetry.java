package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.account.model.valobj.OwnerType;

/** Low-cardinality upload telemetry boundary. */
public interface MaterialUploadTelemetry {
    MaterialUploadTelemetry NOOP = (ownerType, result, bytes, mediaType) -> { };
    void record(OwnerType ownerType, String result, long bytes, String mediaType);
}
