package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlProcessingQueueAdapterTest {

    @Test
    void claimPassesTheWorkerProcessingProfileToTheDatabaseRouter() {
        AtomicReference<String> routedProfile = new AtomicReference<>();
        AtomicReference<String> routedGeneration = new AtomicReference<>();
        IProcessingJobMapper mapper = (IProcessingJobMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IProcessingJobMapper.class},
                (proxy, method, args) -> {
                    if ("selectClaimableForUpdate".equals(method.getName())) {
                        routedProfile.set((String) args[2]);
                        routedGeneration.set((String) args[3]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        var claimed = new MySqlProcessingQueueAdapter(mapper).claim("worker-1", Instant.EPOCH,
                Duration.ofMinutes(2), Set.of(ProcessingJobStage.PROMOTE_ORIGINAL), "d".repeat(64));

        assertTrue(claimed.isEmpty());
        assertEquals("d".repeat(64), routedProfile.get());
        assertEquals(null, routedGeneration.get());
    }

    @Test
    void claimRoutesVectorJobsToOneGenerationProfile() {
        AtomicReference<String> routedGeneration = new AtomicReference<>();
        IProcessingJobMapper mapper = (IProcessingJobMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IProcessingJobMapper.class},
                (proxy, method, args) -> {
                    if ("selectClaimableForUpdate".equals(method.getName())) {
                        routedGeneration.set((String) args[3]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        new MySqlProcessingQueueAdapter(mapper).claim("worker-1", Instant.EPOCH,
                Duration.ofMinutes(2), Set.of(ProcessingJobStage.BUILD_COMPATIBILITY_PROJECTION),
                "d".repeat(64), "ig_target");

        assertEquals("ig_target", routedGeneration.get());
    }
}
