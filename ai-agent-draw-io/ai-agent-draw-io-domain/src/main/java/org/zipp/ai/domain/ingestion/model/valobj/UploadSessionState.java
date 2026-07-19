package org.zipp.ai.domain.ingestion.model.valobj;

public enum UploadSessionState {
    CREATED,
    OBJECT_VERSION_PINNED,
    PROCESSING,
    SUCCEEDED,
    REJECTED,
    CANCELLED,
    EXPIRED
}
