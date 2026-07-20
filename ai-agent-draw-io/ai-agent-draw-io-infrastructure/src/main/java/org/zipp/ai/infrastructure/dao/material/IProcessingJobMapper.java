package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingJobPO;

import java.time.Instant;
import java.util.List;

@Mapper
public interface IProcessingJobMapper {
    int insert(ProcessingJobPO job);
    ProcessingJobPO selectClaimableForUpdate(@Param("now") Instant now,
                                             @Param("stages") List<String> stages,
                                             @Param("processingFingerprint") String processingFingerprint,
                                             @Param("projectionGenerationId") String projectionGenerationId);
    ProcessingJobPO selectById(@Param("id") String id);
    int claim(@Param("id") String id, @Param("workerId") String workerId,
              @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil);
    int heartbeat(@Param("id") String id, @Param("workerId") String workerId,
                  @Param("fenceToken") long fenceToken, @Param("now") Instant now,
                  @Param("leaseUntil") Instant leaseUntil);
    int succeed(@Param("id") String id, @Param("workerId") String workerId,
                @Param("fenceToken") long fenceToken);
    int retry(@Param("id") String id, @Param("workerId") String workerId,
              @Param("fenceToken") long fenceToken, @Param("errorCode") String errorCode,
              @Param("retryAt") Instant retryAt);
    int fail(@Param("id") String id, @Param("workerId") String workerId,
             @Param("fenceToken") long fenceToken, @Param("errorCode") String errorCode);
    int requeueExpiredLeases(@Param("now") Instant now, @Param("limit") int limit);
    int requeueFailedByRevision(@Param("revisionId") String revisionId,
                                @Param("retryAt") Instant retryAt);
}
