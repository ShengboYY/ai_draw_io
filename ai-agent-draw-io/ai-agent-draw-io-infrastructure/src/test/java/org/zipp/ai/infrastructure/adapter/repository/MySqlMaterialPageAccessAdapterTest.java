package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionProfile;
import org.zipp.ai.domain.ingestion.service.ProcessingRevisionFingerprintPolicy;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.service.MaterialRevisionPolicy;
import org.zipp.ai.infrastructure.dao.material.IMaterialPreviewMapper;
import org.zipp.ai.infrastructure.dao.material.IMaterializationMapper;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.po.*;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MySqlMaterialPageAccessAdapterTest {
    private static final String PROFILE = "a".repeat(64);
    private static final CatalogOwner OWNER = new CatalogOwner(OwnerType.USER, "user_1");

    @Test
    void persistsDomainRevisionWorkerProfileAndMaterialScopedRequestAtomically() {
        MaterialReprocessPlan plan = plan(Set.of(2), "request-1");
        AtomicReference<ProcessingRevisionPO> revision = new AtomicReference<>();
        AtomicReference<ProcessingJobPO> job = new AtomicReference<>();
        AtomicReference<String> requestRevision = new AtomicReference<>();
        IMaterialPreviewMapper mapper = proxy(IMaterialPreviewMapper.class, (method, args) -> switch (method) {
            case "selectReprocessSourceForUpdate" -> source(Set.of());
            case "selectRequest", "selectByProcessingFingerprint" -> null;
            case "insertRequest" -> {
                requestRevision.set((String) args[4]);
                yield 1;
            }
            case "selectReprocessResult" -> result(plan.revision().id(), 2);
            default -> unsupported(method);
        });
        IMaterializationMapper materialization = proxy(IMaterializationMapper.class, (method, args) -> {
            if ("insertRevision".equals(method)) {
                revision.set((ProcessingRevisionPO) args[0]);
                return 1;
            }
            return unsupported(method);
        });
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                job.set((ProcessingJobPO) args[0]);
                return 1;
            }
            return unsupported(method);
        });

        MaterialReprocessResult created = new MySqlMaterialPageAccessAdapter(
                mapper, materialization, jobs).createOrFindRevision(plan);

        assertFalse(created.reused());
        assertEquals(ProcessingRevisionFingerprintPolicy.processingFingerprint(PROFILE, Set.of(2)),
                revision.get().getFingerprint());
        assertEquals(PROFILE, revision.get().getWorkerProfileFingerprint());
        assertEquals("PROCESSING", revision.get().getState());
        assertEquals("EXTRACT_NATIVE", job.get().getStage());
        assertEquals(plan.revision().id(), requestRevision.get());
    }

    @Test
    void sameProcessingFingerprintReusesRevisionAndRecordsTheNewRequestKey() {
        MaterialReprocessPlan plan = plan(Set.of(), "request-2");
        MaterialReprocessResultPO existing = result("revision_1", 1);
        IMaterialPreviewMapper mapper = proxy(IMaterialPreviewMapper.class, (method, args) -> switch (method) {
            case "selectReprocessSourceForUpdate" -> source(Set.of());
            case "selectRequest" -> null;
            case "selectByProcessingFingerprint" -> existing;
            case "insertRequest" -> 1;
            default -> unsupported(method);
        });
        var adapter = new MySqlMaterialPageAccessAdapter(mapper,
                proxy(IMaterializationMapper.class, (method, args) -> {
                    throw new AssertionError("same processing revision must not be inserted");
                }), proxy(IProcessingJobMapper.class, (method, args) -> {
                    throw new AssertionError("same processing job must not be inserted");
                }));

        MaterialReprocessResult reused = adapter.createOrFindRevision(plan);

        assertTrue(reused.reused());
        assertEquals("revision_1", reused.revisionId());
    }

    @Test
    void failedMatchingRevisionIsReopenedAndItsFailedStageIsRequeued() {
        MaterialReprocessPlan plan = plan(Set.of(), "request-3");
        MaterialReprocessResultPO failed = failedResult();
        AtomicReference<String> restartedState = new AtomicReference<>();
        AtomicReference<Instant> retryAt = new AtomicReference<>();
        IMaterialPreviewMapper mapper = proxy(IMaterialPreviewMapper.class, (method, args) -> switch (method) {
            case "selectReprocessSourceForUpdate" -> source(Set.of());
            case "selectRequest" -> null;
            case "selectByProcessingFingerprint" -> failed;
            case "restartFailedRevision" -> {
                restartedState.set((String) args[1]);
                yield 1;
            }
            case "insertRequest" -> 1;
            case "selectReprocessResult" -> result("revision_1", 1);
            default -> unsupported(method);
        });
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class, (method, args) -> {
            if ("requeueFailedByRevision".equals(method)) {
                retryAt.set((Instant) args[1]);
                return 1;
            }
            return unsupported(method);
        });

        MaterialReprocessResult retried = new MySqlMaterialPageAccessAdapter(mapper,
                proxy(IMaterializationMapper.class, (method, args) -> {
                    throw new AssertionError("failed revision must be resumed, not duplicated");
                }), jobs).createOrFindRevision(plan);

        assertTrue(retried.reused());
        assertEquals("PROCESSING", restartedState.get());
        assertEquals(plan.requestedAt(), retryAt.get());
    }

    private MaterialReprocessPlan plan(Set<Integer> excluded, String key) {
        return new MaterialRevisionPolicy().plan(OWNER, snapshot(Set.of()), excluded, key,
                profile(), prefix -> prefix + "_2", Instant.parse("2026-07-20T00:00:00Z"));
    }

    private MaterialReprocessSnapshot snapshot(Set<Integer> excluded) {
        return new MaterialReprocessSnapshot("material_1", "version_1", 3, "b".repeat(64),
                "revision_1", 1, "READY",
                ProcessingRevisionFingerprintPolicy.processingFingerprint(PROFILE, excluded),
                profile(), excluded);
    }

    private MaterialReprocessSourcePO source(Set<Integer> excluded) {
        MaterialReprocessSourcePO po = new MaterialReprocessSourcePO();
        po.setMaterialId("material_1");
        po.setVersionId("version_1");
        po.setPageCount(3);
        po.setContentSha256("b".repeat(64));
        po.setLatestRevisionId("revision_1");
        po.setLatestRevisionNo(1);
        po.setLatestRevisionState("READY");
        po.setProcessingFingerprint(ProcessingRevisionFingerprintPolicy.processingFingerprint(PROFILE, excluded));
        po.setWorkerProfileFingerprint(PROFILE);
        po.setParserVersion(profile().parserVersion());
        po.setCleanerVersion(profile().cleanerVersion());
        po.setChunkSchemaVersion(profile().chunkSchemaVersion());
        po.setOcrVersion(profile().ocrVersion());
        po.setVlmSchemaVersion(profile().vlmSchemaVersion());
        po.setExcludedPagesJson("[]");
        return po;
    }

    private ProcessingRevisionProfile profile() {
        return new ProcessingRevisionProfile(PROFILE, "parser-v1", "cleaner-v1", "chunk-v1",
                "ocr-v1", "visual-v1");
    }

    private MaterialReprocessResultPO result(String revisionId, int revisionNo) {
        MaterialReprocessResultPO po = new MaterialReprocessResultPO();
        po.setMaterialId("material_1");
        po.setVersionId("version_1");
        po.setRevisionId(revisionId);
        po.setRevisionNo(revisionNo);
        po.setIngestState("READY");
        po.setRevisionState(revisionNo == 1 ? "READY" : "PROCESSING");
        po.setRevisionStage(revisionNo == 1 ? "PUBLISHING" : "CREATED");
        return po;
    }

    private MaterialReprocessResultPO failedResult() {
        MaterialReprocessResultPO po = result("revision_1", 1);
        po.setRevisionState("FAILED");
        po.setRevisionStage("EXTRACTING");
        po.setProgress(20);
        po.setProcessingFingerprint(ProcessingRevisionFingerprintPolicy.processingFingerprint(
                PROFILE, Set.of()));
        po.setExcludedPagesJson("[]");
        po.setErrorCode("OCR_FAILED");
        return po;
    }

    private static Object unsupported(String method) {
        throw new AssertionError("unexpected mapper call: " + method);
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
