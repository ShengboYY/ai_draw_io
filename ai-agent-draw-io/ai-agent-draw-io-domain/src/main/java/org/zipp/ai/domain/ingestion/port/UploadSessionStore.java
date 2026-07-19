package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.UploadQuotaSnapshot;
import org.zipp.ai.domain.ingestion.model.valobj.UploadRateReservation;

import java.time.Instant;
import java.util.Optional;

public interface UploadSessionStore {
    Optional<UploadSession> findByOwnerAndIdempotencyKey(OwnerType ownerType, String ownerKey,
                                                         String idempotencyKey);
    Optional<UploadSession> findByIdForOwner(String uploadId, OwnerType ownerType, String ownerKey);
    UploadQuotaSnapshot quotaSnapshot(OwnerType ownerType, String ownerKey, String ipRateKey,
                                      Instant hourBucket);
    UploadSession createAndConsumeRate(UploadSession session, String ipRateKey, Instant hourBucket,
                                       UploadRateReservation reservation);
    UploadSession pinAndEnqueue(UploadSession session, ProcessingJob job, int processingLimit);
    UploadSession expire(UploadSession session);
}
