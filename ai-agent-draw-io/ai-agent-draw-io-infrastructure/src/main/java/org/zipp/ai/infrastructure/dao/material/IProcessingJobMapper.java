package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingJobPO;

import java.time.Instant;

@Mapper
public interface IProcessingJobMapper {
    int insert(ProcessingJobPO job);
    ProcessingJobPO selectClaimableForUpdate(@Param("now") Instant now);
    int claim(@Param("id") String id, @Param("workerId") String workerId,
              @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil);
    int heartbeat(@Param("id") String id, @Param("workerId") String workerId,
                  @Param("fenceToken") long fenceToken, @Param("leaseUntil") Instant leaseUntil);
    int succeed(@Param("id") String id, @Param("workerId") String workerId,
                @Param("fenceToken") long fenceToken);
}
