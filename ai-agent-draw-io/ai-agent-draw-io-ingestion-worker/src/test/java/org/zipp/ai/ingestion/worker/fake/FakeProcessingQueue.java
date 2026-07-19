package org.zipp.ai.ingestion.worker.fake;

import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobLease;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStatus;
import org.zipp.ai.domain.ingestion.port.ProcessingQueuePort;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class FakeProcessingQueue implements ProcessingQueuePort {
    private final Map<String, ProcessingJob> jobs = new LinkedHashMap<>();

    @Override
    public synchronized void enqueue(ProcessingJob job) {
        if (jobs.putIfAbsent(job.id(), job) != null) {
            throw new IllegalArgumentException("job already exists");
        }
    }

    @Override
    public synchronized Optional<ProcessingJobLease> claim(String workerId, Instant now, Duration leaseDuration) {
        return jobs.values().stream()
                .filter(job -> job.status() == ProcessingJobStatus.QUEUED || job.status() == ProcessingJobStatus.RETRY)
                .filter(job -> !job.notBefore().isAfter(now))
                .sorted(Comparator.comparingInt(ProcessingJob::priority).reversed().thenComparing(ProcessingJob::id))
                .findFirst()
                .map(job -> new ProcessingJobLease(job, job.claim(workerId, now, leaseDuration)));
    }

    @Override
    public synchronized boolean heartbeat(String jobId, String workerId, long fenceToken,
                                          Instant now, Duration extension) {
        ProcessingJob job = jobs.get(jobId);
        return job != null && job.heartbeat(workerId, fenceToken, now, extension);
    }

    @Override
    public synchronized boolean succeed(String jobId, String workerId, long fenceToken) {
        ProcessingJob job = jobs.get(jobId);
        return job != null && job.succeed(workerId, fenceToken);
    }

    @Override
    public synchronized boolean retry(String jobId, String workerId, long fenceToken,
                                      String errorCode, Instant retryAt) {
        ProcessingJob job = jobs.get(jobId);
        return job != null && job.retry(workerId, fenceToken, errorCode, retryAt);
    }

    @Override
    public synchronized boolean fail(String jobId, String workerId, long fenceToken, String errorCode) {
        ProcessingJob job = jobs.get(jobId);
        return job != null && job.fail(workerId, fenceToken, errorCode);
    }
}
