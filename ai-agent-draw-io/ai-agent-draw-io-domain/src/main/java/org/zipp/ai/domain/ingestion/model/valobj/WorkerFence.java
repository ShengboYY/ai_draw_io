package org.zipp.ai.domain.ingestion.model.valobj;

public record WorkerFence(String jobId, String workerId, long fenceToken) {
    public WorkerFence {
        if (jobId == null || jobId.isBlank() || workerId == null || workerId.isBlank()
                || fenceToken < 1) {
            throw new IllegalArgumentException("complete fenced worker identity is required");
        }
    }
}
