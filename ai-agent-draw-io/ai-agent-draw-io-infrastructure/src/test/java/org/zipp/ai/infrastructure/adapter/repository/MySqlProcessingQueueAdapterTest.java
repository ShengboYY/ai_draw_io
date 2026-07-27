package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlProcessingQueueAdapterTest {

    @Test
    void claimPassesTheWorkerProcessingProfileToTheDatabaseRouter() {
        AtomicReference<List<String>> routedProfiles = new AtomicReference<>();
        AtomicReference<String> routedGeneration = new AtomicReference<>();
        IProcessingJobMapper mapper = (IProcessingJobMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IProcessingJobMapper.class},
                (proxy, method, args) -> {
                    if ("selectClaimableForUpdate".equals(method.getName())) {
                        @SuppressWarnings("unchecked")
                        List<String> profiles = (List<String>) args[2];
                        routedProfiles.set(profiles);
                        routedGeneration.set((String) args[3]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        var claimed = new MySqlProcessingQueueAdapter(mapper).claim("worker-1", Instant.EPOCH,
                Duration.ofMinutes(2), Set.of(ProcessingJobStage.PROMOTE_ORIGINAL), "d".repeat(64));

        assertTrue(claimed.isEmpty());
        assertEquals(List.of("d".repeat(64)), routedProfiles.get());
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

    @Test
    void claimRoutesEveryExplicitlyCompatibleProcessingProfile() {
        AtomicReference<List<String>> routedProfiles = new AtomicReference<>();
        IProcessingJobMapper mapper = (IProcessingJobMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IProcessingJobMapper.class},
                (proxy, method, args) -> {
                    if ("selectClaimableForUpdate".equals(method.getName())) {
                        @SuppressWarnings("unchecked")
                        List<String> profiles = (List<String>) args[2];
                        routedProfiles.set(profiles);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        new MySqlProcessingQueueAdapter(mapper).claim("worker-1", Instant.EPOCH,
                Duration.ofMinutes(2), Set.of(ProcessingJobStage.BUILD_LEXICAL_PROJECTION),
                Set.of("b".repeat(64), "a".repeat(64)), "ig_target");

        assertEquals(List.of("a".repeat(64), "b".repeat(64)), routedProfiles.get());
    }

    @Test
    void stalledRecoveryOnlyRoutesExplicitlyCompatibleUnclaimedProfiles() {
        AtomicReference<Instant> routedCutoff = new AtomicReference<>();
        AtomicReference<List<String>> routedStages = new AtomicReference<>();
        AtomicReference<List<String>> routedProfiles = new AtomicReference<>();
        AtomicReference<String> routedGeneration = new AtomicReference<>();
        IProcessingJobMapper mapper = (IProcessingJobMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IProcessingJobMapper.class},
                (proxy, method, args) -> {
                    if ("prioritizeStalledUnclaimed".equals(method.getName())) {
                        routedCutoff.set((Instant) args[1]);
                        @SuppressWarnings("unchecked")
                        List<String> stages = (List<String>) args[2];
                        @SuppressWarnings("unchecked")
                        List<String> profiles = (List<String>) args[3];
                        routedStages.set(stages);
                        routedProfiles.set(profiles);
                        routedGeneration.set((String) args[4]);
                        return 2;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        int recovered = new MySqlProcessingQueueAdapter(mapper).prioritizeStalledUnclaimed(
                Instant.parse("2026-07-25T00:00:00Z"),
                Instant.parse("2026-07-24T23:45:00Z"),
                Set.of(ProcessingJobStage.BUILD_LEXICAL_PROJECTION),
                Set.of("b".repeat(64), "a".repeat(64)), "ig_target", 10);

        assertEquals(2, recovered);
        assertEquals(Instant.parse("2026-07-24T23:45:00Z"), routedCutoff.get());
        assertEquals(List.of("BUILD_LEXICAL_PROJECTION"), routedStages.get());
        assertEquals(List.of("a".repeat(64), "b".repeat(64)), routedProfiles.get());
        assertEquals("ig_target", routedGeneration.get());
    }
}
