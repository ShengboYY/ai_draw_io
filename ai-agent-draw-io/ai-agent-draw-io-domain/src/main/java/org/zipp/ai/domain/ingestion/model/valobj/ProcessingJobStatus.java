package org.zipp.ai.domain.ingestion.model.valobj;

public enum ProcessingJobStatus {
    QUEUED,
    RUNNING,
    RETRY,
    SUCCEEDED,
    FAILED
}
