package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.SecurityValidationResult;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;
import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;

import java.util.Optional;

public interface SecureUploadWorkPort {
    Optional<UploadSession> findById(String uploadId);
    Optional<UploadSession> beginProcessing(String uploadId, long expectedGeneration, WorkerFence fence);
    boolean reject(String uploadId, long expectedGeneration, UploadErrorCode reason, WorkerFence fence);
    boolean commitSecurityValidation(String uploadId, long expectedGeneration,
                                     SecurityValidationResult result, ProcessingJob nextJob, WorkerFence fence);
}
