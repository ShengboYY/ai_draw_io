package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.MaterialOperationalSnapshotPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialProviderCapacityPO;

import java.time.Instant;

@Mapper
public interface IMaterialOperationsMapper {
    MaterialOperationalSnapshotPO selectSnapshot(@Param("now") Instant now,
                                                  @Param("last24Hours") Instant last24Hours,
                                                  @Param("monthStart") Instant monthStart,
                                                  @Param("stuckBefore") Instant stuckBefore,
                                                  @Param("reconciliationStaleBefore") Instant reconciliationStaleBefore);

    MaterialProviderCapacityPO selectProviderCapacity();

    int upsertProviderCapacity(@Param("capturedAt") Instant capturedAt,
                               @Param("sequence") long sequence,
                               @Param("embeddingPercent") double embeddingPercent,
                               @Param("vectorReadPercent") double vectorReadPercent,
                               @Param("vectorWritePercent") double vectorWritePercent,
                               @Param("dependenciesAvailable") boolean dependenciesAvailable);
}
