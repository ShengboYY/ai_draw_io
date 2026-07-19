package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.MaterializationIds;
import org.zipp.ai.domain.ingestion.model.valobj.MaterializationResult;
import org.zipp.ai.domain.ingestion.model.valobj.OriginalPromotionWork;
import org.zipp.ai.domain.ingestion.model.valobj.PromotedOriginal;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionProfile;
import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;

import java.time.Instant;
import java.util.Optional;

public interface MaterializationWorkPort {
    MaterializationResult resolveAndMaterialize(String uploadId, MaterializationIds ids,
                                                ProcessingRevisionProfile processingProfile,
                                                ProcessingJob promotionJob,
                                                ProcessingJob extractionJob, WorkerFence fence, Instant now);

    Optional<OriginalPromotionWork> findPromotionWork(String uploadId, WorkerFence fence);

    boolean commitPromotion(OriginalPromotionWork work, PromotedOriginal promoted,
                            ProcessingJob extractionJob, WorkerFence fence);
}
