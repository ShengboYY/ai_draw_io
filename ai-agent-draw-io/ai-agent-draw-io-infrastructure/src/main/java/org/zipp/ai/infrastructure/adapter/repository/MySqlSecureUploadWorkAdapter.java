package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.SecurityValidationResult;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;
import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;
import org.zipp.ai.domain.ingestion.port.SecureUploadWorkPort;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.IUploadSessionMapper;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingJobPO;

import java.util.Objects;
import java.util.Optional;

@Repository
public class MySqlSecureUploadWorkAdapter implements SecureUploadWorkPort {

    private final IUploadSessionMapper uploadMapper;
    private final IProcessingJobMapper jobMapper;

    public MySqlSecureUploadWorkAdapter(IUploadSessionMapper uploadMapper, IProcessingJobMapper jobMapper) {
        this.uploadMapper = Objects.requireNonNull(uploadMapper, "uploadMapper");
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper");
    }

    @Override
    public Optional<UploadSession> findById(String uploadId) {
        return Optional.ofNullable(uploadMapper.selectById(uploadId)).map(MySqlUploadSessionStore::toDomain);
    }

    @Override
    @Transactional
    public Optional<UploadSession> beginProcessing(String uploadId, long expectedGeneration, WorkerFence fence) {
        if (uploadMapper.beginProcessing(uploadId, expectedGeneration, fence.jobId(), fence.workerId(),
                fence.fenceToken()) != 1) {
            return Optional.empty();
        }
        return findById(uploadId);
    }

    @Override
    public boolean reject(String uploadId, long expectedGeneration, UploadErrorCode reason, WorkerFence fence) {
        return uploadMapper.reject(uploadId, expectedGeneration, reason.name(), fence.jobId(), fence.workerId(),
                fence.fenceToken()) == 1;
    }

    @Override
    @Transactional
    public boolean commitSecurityValidation(String uploadId, long expectedGeneration,
                                            SecurityValidationResult result, ProcessingJob nextJob,
                                            WorkerFence fence) {
        int updated = uploadMapper.commitSecurityValidation(uploadId, expectedGeneration,
                result.actualSize(), result.actualSha256(), result.detectedMediaType(),
                result.pageCount(), result.pixelCount(), fence.jobId(), fence.workerId(),
                fence.fenceToken());
        if (updated != 1) {
            return false;
        }
        jobMapper.insert(toPo(nextJob));
        return true;
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
        po.setFenceToken(job.fenceToken());
        return po;
    }
}
