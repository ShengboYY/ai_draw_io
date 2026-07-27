package org.zipp.ai.domain.ingestion.model.valobj;

import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;

import java.util.Objects;

public record ProcessingJobLease(ProcessingJob job, long fenceToken) {
    public ProcessingJobLease {
        Objects.requireNonNull(job, "job");
        if (fenceToken < 1) {
            throw new IllegalArgumentException("fenceToken must be positive");
        }
    }
}
