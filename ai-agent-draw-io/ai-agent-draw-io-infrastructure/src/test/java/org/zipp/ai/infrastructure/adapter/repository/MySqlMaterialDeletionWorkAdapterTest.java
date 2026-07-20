package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.material.model.aggregate.MaterialDeletionTask;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.infrastructure.dao.material.IMaterialDeletionMapper;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class MySqlMaterialDeletionWorkAdapterTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void lostFencePreventsAnyDatabasePurge() {
        AtomicBoolean purged = new AtomicBoolean();
        IMaterialDeletionMapper mapper = (IMaterialDeletionMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IMaterialDeletionMapper.class},
                (proxy, method, args) -> {
                    if ("lockRunningTask".equals(method.getName())) return null;
                    throw new AssertionError("lost fence must stop before " + method.getName());
                });
        MySqlMaterialDeletionWorkAdapter adapter = new MySqlMaterialDeletionWorkAdapter(
                mapper, materialId -> purged.set(true));
        MaterialDeletionTask task = MaterialDeletionTask.rehydrate("task_1", "material_1", 2,
                MaterialDeletionStage.PURGE_DATABASE, MaterialDeletionTaskStatus.QUEUED,
                0, NOW, null, null, 0, null);
        task.claim("worker", NOW, Duration.ofMinutes(5));
        long fence = task.fenceToken();
        task.complete();

        assertThrows(IllegalStateException.class, () -> adapter.purgeAndComplete(task,
                MaterialDeletionStage.PURGE_DATABASE, fence, NOW));
        assertFalse(purged.get());
    }
}
