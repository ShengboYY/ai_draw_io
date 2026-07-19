package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobLease;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface ProcessingQueuePort {
    void enqueue(ProcessingJob job);
    Optional<ProcessingJobLease> claim(String workerId, Instant now, Duration leaseDuration);
    boolean heartbeat(String jobId, String workerId, long fenceToken, Instant now, Duration extension);
    boolean succeed(String jobId, String workerId, long fenceToken);
    boolean retry(String jobId, String workerId, long fenceToken, String errorCode, Instant retryAt);
    boolean fail(String jobId, String workerId, long fenceToken, String errorCode);
}
