package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobLease;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStatus;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobTarget;
import org.zipp.ai.domain.ingestion.port.ProcessingQueuePort;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingJobPO;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Repository
public class MySqlProcessingQueueAdapter implements ProcessingQueuePort {

    private final IProcessingJobMapper mapper;

    public MySqlProcessingQueueAdapter(IProcessingJobMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void enqueue(ProcessingJob job) {
        mapper.insert(toPo(Objects.requireNonNull(job, "job")));
    }

    @Override
    @Transactional
    public Optional<ProcessingJobLease> claim(String workerId, Instant now, Duration leaseDuration,
                                              Set<ProcessingJobStage> acceptedStages) {
        return claim(workerId, now, leaseDuration, acceptedStages, null);
    }

    @Override
    @Transactional
    public Optional<ProcessingJobLease> claim(String workerId, Instant now, Duration leaseDuration,
                                              Set<ProcessingJobStage> acceptedStages,
                                              String processingFingerprint) {
        String owner = requireText(workerId, "workerId");
        Instant claimedAt = Objects.requireNonNull(now, "now");
        Duration duration = positive(leaseDuration);
        String profile = processingFingerprint == null ? null : requireFingerprint(processingFingerprint);
        var stages = acceptedStages == null ? java.util.List.<String>of()
                : acceptedStages.stream().map(Enum::name).sorted().toList();
        ProcessingJobPO candidate = mapper.selectClaimableForUpdate(claimedAt, stages, profile);
        if (candidate == null || mapper.claim(candidate.getId(), owner, claimedAt, claimedAt.plus(duration)) != 1) {
            return Optional.empty();
        }
        // Re-read the row after the fenced increment so the worker never guesses its token.
        ProcessingJob claimed = toDomain(mapper.selectById(candidate.getId()));
        return Optional.of(new ProcessingJobLease(claimed, claimed.fenceToken()));
    }

    @Override
    public boolean heartbeat(String jobId, String workerId, long fenceToken, Instant now, Duration extension) {
        Instant heartbeatAt = Objects.requireNonNull(now, "now");
        return mapper.heartbeat(requireText(jobId, "jobId"), requireText(workerId, "workerId"), fenceToken,
                heartbeatAt, heartbeatAt.plus(positive(extension))) == 1;
    }

    @Override
    public boolean succeed(String jobId, String workerId, long fenceToken) {
        return mapper.succeed(requireText(jobId, "jobId"), requireText(workerId, "workerId"), fenceToken) == 1;
    }

    @Override
    public boolean retry(String jobId, String workerId, long fenceToken, String errorCode, Instant retryAt) {
        return mapper.retry(requireText(jobId, "jobId"), requireText(workerId, "workerId"), fenceToken,
                requireText(errorCode, "errorCode"), Objects.requireNonNull(retryAt, "retryAt")) == 1;
    }

    @Override
    public boolean fail(String jobId, String workerId, long fenceToken, String errorCode) {
        return mapper.fail(requireText(jobId, "jobId"), requireText(workerId, "workerId"), fenceToken,
                requireText(errorCode, "errorCode")) == 1;
    }

    @Override
    public int requeueExpiredLeases(Instant now, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return mapper.requeueExpiredLeases(Objects.requireNonNull(now, "now"), limit);
    }

    private static ProcessingJobPO toPo(ProcessingJob job) {
        ProcessingJobPO po = new ProcessingJobPO();
        po.setId(job.id());
        po.setUploadSessionId(job.target().uploadSessionId());
        po.setRevisionId(job.target().revisionId());
        po.setStage(job.stage().name());
        po.setWorkKey(job.workKey());
        po.setInputFingerprint(job.inputFingerprint());
        po.setPriority(job.priority());
        po.setStatus(job.status().name());
        po.setAttempt(job.attempt());
        po.setNotBefore(job.notBefore());
        po.setLeaseOwner(job.leaseOwner());
        po.setLeaseUntil(job.leaseUntil());
        po.setFenceToken(job.fenceToken());
        po.setLastErrorCode(job.lastErrorCode());
        return po;
    }

    private static ProcessingJob toDomain(ProcessingJobPO po) {
        Objects.requireNonNull(po, "persisted job");
        return ProcessingJob.rehydrate(po.getId(),
                new ProcessingJobTarget(po.getUploadSessionId(), po.getRevisionId()),
                ProcessingJobStage.valueOf(po.getStage()), po.getWorkKey(), po.getInputFingerprint(),
                po.getPriority(), ProcessingJobStatus.valueOf(po.getStatus()), po.getAttempt(),
                po.getNotBefore(), po.getLeaseOwner(), po.getLeaseUntil(), po.getFenceToken(),
                po.getLastErrorCode());
    }

    private static Duration positive(Duration duration) {
        Duration value = Objects.requireNonNull(duration, "duration");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String requireFingerprint(String value) {
        String fingerprint = requireText(value, "processingFingerprint");
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("processingFingerprint must be lowercase SHA-256");
        }
        return fingerprint;
    }
}
