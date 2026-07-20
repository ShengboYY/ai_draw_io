package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.operations.MaterialProviderCapacityFeed;
import org.zipp.ai.domain.operations.MaterialProviderCapacitySnapshot;
import org.zipp.ai.infrastructure.dao.material.IMaterialOperationsMapper;
import org.zipp.ai.infrastructure.dao.material.po.MaterialProviderCapacityPO;

import java.util.Objects;

/** Shared monotonic provider-capacity feed used consistently by every API task. */
@Repository
public class MySqlMaterialProviderCapacityFeed implements MaterialProviderCapacityFeed {
    private final IMaterialOperationsMapper mapper;

    public MySqlMaterialProviderCapacityFeed(IMaterialOperationsMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public MaterialProviderCapacitySnapshot current() {
        MaterialProviderCapacityPO row = Objects.requireNonNull(
                mapper.selectProviderCapacity(), "provider capacity has not been reported");
        return new MaterialProviderCapacitySnapshot(row.getCapturedAt(), row.getSequence(),
                row.getEmbeddingPercent(), row.getVectorReadPercent(), row.getVectorWritePercent(),
                row.isDependenciesAvailable());
    }

    @Override
    public boolean update(MaterialProviderCapacitySnapshot snapshot) {
        MaterialProviderCapacitySnapshot value = Objects.requireNonNull(snapshot, "snapshot");
        return mapper.upsertProviderCapacity(value.capturedAt(), value.sequence(), value.embeddingPercent(),
                value.vectorReadPercent(), value.vectorWritePercent(), value.dependenciesAvailable()) > 0;
    }
}
