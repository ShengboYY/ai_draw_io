package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.retrieval.model.valobj.ProjectionBatchInventory;
import org.zipp.ai.infrastructure.dao.material.IIndexProjectionMaintenanceMapper;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.IVectorProjectionMapper;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingJobPO;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlIndexProjectionMaintenanceAdapterTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void missingVectorRepairPersistsAuditBeforeGenerationRoutedJob() {
        List<String> calls = new ArrayList<>();
        IIndexProjectionMaintenanceMapper mapper = proxy(IIndexProjectionMaintenanceMapper.class,
                (method, args) -> {
                    calls.add(method);
                    return switch (method) {
                        case "closeTerminalRepair" -> 0;
                        case "insertRepairAudit" -> 1;
                        default -> unsupported(method);
                    };
                });
        AtomicReference<ProcessingJobPO> queued = new AtomicReference<>();
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class, (method, args) -> {
            calls.add(method);
            if ("insert".equals(method)) {
                queued.set((ProcessingJobPO) args[0]);
                return 1;
            }
            return unsupported(method);
        });
        MySqlIndexProjectionMaintenanceAdapter adapter = adapter(mapper, jobs);
        ProjectionBatchInventory batch = new ProjectionBatchInventory("ig_1", "rev_1", 0,
                "a".repeat(64), List.of("vector_1", "vector_2"));

        assertTrue(adapter.scheduleMissingVectorRepair(batch, Set.of("vector_2"), NOW));

        assertTrue(calls.indexOf("insertRepairAudit") < calls.indexOf("insert"));
        assertEquals(ProcessingJobStage.REPAIR_VECTOR_BATCH.name(), queued.get().getStage());
        assertTrue(queued.get().getWorkKey().startsWith("ig:ig_1:repair:"));
    }

    @Test
    void failedCompatibilityTargetIsAuditedBeforeItsTerminalJobIsRequeued() {
        List<String> calls = new ArrayList<>();
        ProcessingJobPO failed = new ProcessingJobPO();
        failed.setId("job_failed");
        failed.setLastErrorCode("TRANSIENT_DEPENDENCY");
        IIndexProjectionMaintenanceMapper mapper = proxy(IIndexProjectionMaintenanceMapper.class,
                (method, args) -> {
                    calls.add(method);
                    return switch (method) {
                        case "lockTargetState" -> "PLANNED";
                        case "selectFailedTargetJobForUpdate" -> failed;
                        case "insertTargetRepairAudit", "requeueFailedJob" -> 1;
                        default -> unsupported(method);
                    };
                });

        assertTrue(adapter(mapper, unusedJobs()).retryFailedTarget(
                "ig_1", "rev_1", "b".repeat(64), "OPERATOR_RETRY", NOW));

        assertTrue(calls.indexOf("lockTargetState") < calls.indexOf("selectFailedTargetJobForUpdate"));
        assertTrue(calls.indexOf("insertTargetRepairAudit") < calls.indexOf("requeueFailedJob"));
    }

    @Test
    void retiredCleanupClaimsTheGenerationBeforeReadingExactVectorIds() {
        List<String> calls = new ArrayList<>();
        IIndexProjectionMaintenanceMapper mapper = proxy(IIndexProjectionMaintenanceMapper.class,
                (method, args) -> {
                    calls.add(method);
                    return switch (method) {
                        case "claimRetiredGenerationCleanup" -> 1;
                        case "selectRetiredEligibleAt" -> NOW;
                        case "selectRetiredVectorIds" -> List.of("vector_old");
                        default -> unsupported(method);
                    };
                });

        var cleanup = adapter(mapper, unusedJobs()).findRetiredCleanup(
                "ig_1", NOW, java.time.Duration.ofHours(24), 100).orElseThrow();

        assertEquals(List.of("vector_old"), cleanup.vectorIds());
        assertTrue(calls.indexOf("claimRetiredGenerationCleanup")
                < calls.indexOf("selectRetiredVectorIds"));
    }

    @Test
    void providerCursorAndOrphanAuditAreDurableBeforeExternalWork() {
        List<String> calls = new ArrayList<>();
        AtomicReference<String> deletionId = new AtomicReference<>();
        IIndexProjectionMaintenanceMapper mapper = proxy(IIndexProjectionMaintenanceMapper.class,
                (method, args) -> {
                    calls.add(method);
                    return switch (method) {
                        case "insertProviderCursor", "upsertOrphanDeletionIntent",
                                "completeOrphanDeletion", "advanceProviderCursor" -> {
                            if ("upsertOrphanDeletionIntent".equals(method)) {
                                deletionId.set((String) args[0]);
                            }
                            yield 1;
                        }
                        case "selectProviderCursor" -> "page_1";
                        default -> unsupported(method);
                    };
                });
        MySqlIndexProjectionMaintenanceAdapter adapter = adapter(mapper, unusedJobs());

        assertEquals("page_1", adapter.findProviderCursor("ig_1", NOW));
        String auditId = adapter.recordOrphanDeletionIntent(
                "ig_1", List.of("vector_2", "vector_1"), NOW);
        assertEquals(deletionId.get(), auditId);
        assertTrue(adapter.completeOrphanDeletion(auditId, NOW));
        assertTrue(adapter.advanceProviderCursor("ig_1", "page_1", "page_2", NOW));
        assertTrue(calls.indexOf("insertProviderCursor") < calls.indexOf("selectProviderCursor"));
        assertTrue(calls.indexOf("upsertOrphanDeletionIntent") < calls.indexOf("completeOrphanDeletion"));
    }

    private MySqlIndexProjectionMaintenanceAdapter adapter(
            IIndexProjectionMaintenanceMapper mapper, IProcessingJobMapper jobs) {
        return new MySqlIndexProjectionMaintenanceAdapter(mapper, jobs,
                proxy(IVectorProjectionMapper.class, (method, args) -> unsupported(method)));
    }

    private IProcessingJobMapper unusedJobs() {
        return proxy(IProcessingJobMapper.class, (method, args) -> unsupported(method));
    }

    private static Object unsupported(String method) {
        throw new UnsupportedOperationException(method);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.invoke(method.getName(), args == null ? new Object[0] : args));
    }

    @FunctionalInterface
    private interface Call {
        Object invoke(String method, Object[] args);
    }
}
