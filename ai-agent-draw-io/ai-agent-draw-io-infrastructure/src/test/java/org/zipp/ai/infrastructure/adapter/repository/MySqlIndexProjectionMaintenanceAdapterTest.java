package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.retrieval.model.valobj.PendingProjectionDeletion;
import org.zipp.ai.domain.retrieval.model.valobj.ProjectionBatchInventory;
import org.zipp.ai.domain.retrieval.model.valobj.TemporaryProjectionCleanup;
import org.zipp.ai.infrastructure.dao.material.IIndexProjectionMaintenanceMapper;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.IVectorProjectionMapper;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingJobPO;
import org.zipp.ai.infrastructure.dao.material.po.RagIndexGenerationPO;

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
        RagIndexGenerationPO retired = new RagIndexGenerationPO();
        retired.setId("ig_retired");
        retired.setNamespace("retired-namespace");
        IIndexProjectionMaintenanceMapper mapper = proxy(IIndexProjectionMaintenanceMapper.class,
                (method, args) -> {
                    calls.add(method);
                    return switch (method) {
                        case "selectRetiredCleanupGeneration" -> retired;
                        case "claimRetiredGenerationCleanup" -> 1;
                        case "selectRetiredEligibleAt" -> NOW;
                        case "selectRetiredVectorIds" -> List.of("vector_old");
                        default -> unsupported(method);
                    };
                });

        var cleanup = adapter(mapper, unusedJobs()).findRetiredCleanup(
                NOW, java.time.Duration.ofHours(24), 100).orElseThrow();

        assertEquals("ig_retired", cleanup.generationId());
        assertEquals("retired-namespace", cleanup.namespace());
        assertEquals(List.of("vector_old"), cleanup.vectorIds());
        assertTrue(calls.indexOf("claimRetiredGenerationCleanup")
                < calls.indexOf("selectRetiredVectorIds"));
    }

    @Test
    void temporaryCleanupReadsOneMaterialThenConditionallyTombstonesItsExactIds() {
        List<String> calls = new ArrayList<>();
        RagIndexGenerationPO pendingGeneration = new RagIndexGenerationPO();
        pendingGeneration.setId("ig_1");
        pendingGeneration.setNamespace("namespace-1");
        IIndexProjectionMaintenanceMapper mapper = proxy(IIndexProjectionMaintenanceMapper.class,
                (method, args) -> {
                    calls.add(method);
                    return switch (method) {
                        case "insertTemporaryCleanupCursor", "advanceTemporaryCleanupCursor" -> 1;
                        case "selectTemporaryCleanupCursor" -> "material_previous";
                        case "selectTemporaryCleanupMaterial" -> "material_temp";
                        case "selectTemporaryCleanupVectorIds" -> List.of("vector_1", "vector_2");
                        case "lockExpiredTemporaryCleanupMaterial" -> "material_temp";
                        case "claimTemporaryConversationVectorsForDeletion",
                                "completeTemporaryProviderDeletion" -> 2;
                        case "selectPendingProviderDeletionGeneration" -> pendingGeneration;
                        case "selectTemporaryProviderDeletionRetries" -> List.of("vector_1", "vector_2");
                        default -> unsupported(method);
                    };
                });
        MySqlIndexProjectionMaintenanceAdapter adapter = adapter(mapper, unusedJobs());

        assertEquals("material_previous", adapter.findTemporaryCleanupCursor("ig_1", NOW));
        assertTrue(adapter.advanceTemporaryCleanupCursor(
                "ig_1", "material_previous", "material_temp", NOW));
        TemporaryProjectionCleanup cleanup = adapter.findTemporaryConversationCleanup(
                "ig_1", "material_previous", NOW, 100).orElseThrow();

        assertEquals("material_temp", cleanup.materialId());
        assertEquals(List.of("vector_1", "vector_2"), cleanup.vectorIds());
        assertTrue(adapter.claimTemporaryConversationVectorsForDeletion(cleanup, NOW));
        PendingProjectionDeletion retry = adapter.findTemporaryProviderDeletionRetry(100).orElseThrow();
        assertEquals("ig_1", retry.generationId());
        assertEquals("namespace-1", retry.namespace());
        assertEquals(List.of("vector_1", "vector_2"), retry.vectorIds());
        assertTrue(adapter.completeTemporaryProviderDeletion(
                "ig_1", List.of("vector_1", "vector_2"), NOW));
        assertTrue(calls.indexOf("insertTemporaryCleanupCursor")
                < calls.indexOf("selectTemporaryCleanupCursor"));
        assertTrue(calls.indexOf("selectTemporaryCleanupMaterial")
                < calls.indexOf("selectTemporaryCleanupVectorIds"));
        assertTrue(calls.indexOf("selectTemporaryCleanupVectorIds")
                < calls.indexOf("lockExpiredTemporaryCleanupMaterial"));
        assertTrue(calls.indexOf("lockExpiredTemporaryCleanupMaterial")
                < calls.indexOf("claimTemporaryConversationVectorsForDeletion"));
        assertTrue(calls.indexOf("claimTemporaryConversationVectorsForDeletion")
                < calls.indexOf("completeTemporaryProviderDeletion"));
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
