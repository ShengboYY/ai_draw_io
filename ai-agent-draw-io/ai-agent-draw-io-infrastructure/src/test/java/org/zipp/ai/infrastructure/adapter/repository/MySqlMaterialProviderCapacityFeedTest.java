package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.operations.MaterialProviderCapacitySnapshot;
import org.zipp.ai.infrastructure.dao.material.IMaterialOperationsMapper;
import org.zipp.ai.infrastructure.dao.material.po.MaterialOperationalSnapshotPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialProviderCapacityPO;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlMaterialProviderCapacityFeedTest {
    @Test
    void sharedStoreMakesInstancesConsistentAndRejectsOutOfOrderSamples() {
        FakeSharedMapper mapper = new FakeSharedMapper();
        MySqlMaterialProviderCapacityFeed firstTask = new MySqlMaterialProviderCapacityFeed(mapper);
        MySqlMaterialProviderCapacityFeed secondTask = new MySqlMaterialProviderCapacityFeed(mapper);
        Instant now = Instant.parse("2026-07-20T00:00:00Z");

        assertTrue(firstTask.update(new MaterialProviderCapacitySnapshot(now, 20, 96, 10, 10, true)));
        assertFalse(secondTask.update(new MaterialProviderCapacitySnapshot(now.minusSeconds(30),
                19, 5, 5, 5, true)));

        assertEquals(96D, secondTask.current().embeddingPercent());
        assertEquals(20, secondTask.current().sequence());
    }

    private static final class FakeSharedMapper implements IMaterialOperationsMapper {
        private MaterialProviderCapacityPO value;

        @Override
        public MaterialOperationalSnapshotPO selectSnapshot(Instant now, Instant last24Hours,
                                                             Instant monthStart, Instant stuckBefore,
                                                             Instant reconciliationStaleBefore) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized MaterialProviderCapacityPO selectProviderCapacity() {
            return value;
        }

        @Override
        public synchronized int upsertProviderCapacity(Instant capturedAt, long sequence,
                                                       double embeddingPercent, double vectorReadPercent,
                                                       double vectorWritePercent,
                                                       boolean dependenciesAvailable) {
            if (value != null && sequence <= value.getSequence()) return 0;
            MaterialProviderCapacityPO next = new MaterialProviderCapacityPO();
            next.setCapturedAt(capturedAt);
            next.setSequence(sequence);
            next.setEmbeddingPercent(embeddingPercent);
            next.setVectorReadPercent(vectorReadPercent);
            next.setVectorWritePercent(vectorWritePercent);
            next.setDependenciesAvailable(dependenciesAvailable);
            value = next;
            return 1;
        }
    }
}
