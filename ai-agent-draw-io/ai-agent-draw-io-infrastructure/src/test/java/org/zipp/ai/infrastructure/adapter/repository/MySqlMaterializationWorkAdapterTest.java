package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.MaterializationIds;
import org.zipp.ai.domain.ingestion.model.valobj.MaterializationResult;
import org.zipp.ai.domain.ingestion.model.valobj.OriginalPromotionWork;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobTarget;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionProfile;
import org.zipp.ai.domain.ingestion.model.valobj.PromotedOriginal;
import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;
import org.zipp.ai.infrastructure.dao.material.IMaterializationMapper;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.po.ContentBlobPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialVersionMatchPO;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingJobPO;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingRevisionPO;
import org.zipp.ai.infrastructure.dao.material.po.UploadSessionPO;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlMaterializationWorkAdapterTest {

    private static final Instant NOW = Instant.parse("2026-07-20T02:00:00Z");
    private static final ProcessingRevisionProfile PROFILE = new ProcessingRevisionProfile(
            "d".repeat(64), "pdfbox-test", "canonical-test", "chunk-test", "ocr-test", "visual-test");

    @Test
    void uniqueValidatedContentCreatesOneMaterialGraphAndQueuesPromotion() {
        UploadSessionPO upload = validatedUpload();
        ContentBlobPO blob = new ContentBlobPO();
        blob.setId("blob_1");
        blob.setOwnerType("USER");
        blob.setOwnerKey("usr_1");
        blob.setContentSha256("a".repeat(64));
        blob.setByteSize(42);
        blob.setDetectedMime("application/pdf");
        blob.setOriginalObjectKey("original/blob_1/" + "a".repeat(64));
        blob.setStatus("PROMOTING");
        MaterialPO active = new MaterialPO();
        active.setId("mat_1");
        AtomicReference<ProcessingJobPO> queued = new AtomicReference<>();
        AtomicReference<ProcessingRevisionPO> revision = new AtomicReference<>();
        IMaterializationMapper mapper = proxy(IMaterializationMapper.class, (method, args) -> {
            if ("insertRevision".equals(method)) {
                revision.set((ProcessingRevisionPO) args[0]);
                return 1;
            }
            return switch (method) {
                case "selectUploadForMaterialization" -> upload;
                case "insertContentBlob", "insertMaterial", "insertVersion",
                        "updateLatestVersion", "insertScopeLink", "correlateForPromotion" -> 1;
                case "selectContentBlobForUpdate" -> blob;
                case "selectReusableVersionForUpdate" -> null;
                case "selectActiveMaterialForUpdate" -> active;
                default -> throw new UnsupportedOperationException(method);
            };
        });
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                queued.set((ProcessingJobPO) args[0]);
                return 1;
            }
            throw new UnsupportedOperationException(method);
        });
        var adapter = new MySqlMaterializationWorkAdapter(mapper, jobs);
        ProcessingJob promotion = ProcessingJob.enqueue("job_promote", ProcessingJobTarget.forUpload("upl_1"),
                ProcessingJobStage.PROMOTE_ORIGINAL, "root", "b".repeat(64), 0, NOW);
        ProcessingJob extraction = ProcessingJob.enqueue("job_extract", ProcessingJobTarget.forRevision("rev_1"),
                ProcessingJobStage.EXTRACT_NATIVE, "root", "c".repeat(64), 0, NOW);

        MaterializationResult result = adapter.resolveAndMaterialize("upl_1",
                new MaterializationIds("mat_1", "ver_1", "blob_1", "rev_1", "scope_1"),
                PROFILE, promotion, extraction,
                new WorkerFence("job_resolve", "worker-1", 1), NOW);

        assertEquals(MaterializationResult.Outcome.PROMOTION_QUEUED, result.outcome());
        assertEquals(ProcessingJobStage.PROMOTE_ORIGINAL.name(), queued.get().getStage());
        assertEquals(PROFILE.parserVersion(), revision.get().getParserVersion());
        assertEquals(PROFILE.cleanerVersion(), revision.get().getCleanerVersion());
        assertEquals(PROFILE.ocrVersion(), revision.get().getOcrVersion());
    }

    @Test
    void explicitTargetCreatesItsOwnVersionWhileReusingAnAvailableOwnedBlob() {
        UploadSessionPO upload = validatedUpload();
        upload.setNewVersionOfMaterialId("mat_target");
        ContentBlobPO blob = availableBlob();
        MaterialVersionMatchPO other = new MaterialVersionMatchPO();
        other.setMaterialId("mat_other");
        other.setVersionId("ver_other");
        other.setRevisionId("rev_other");
        other.setContentBlobId("blob_existing");
        MaterialPO target = new MaterialPO();
        target.setId("mat_target");
        AtomicReference<ProcessingJobPO> queued = new AtomicReference<>();
        IMaterializationMapper mapper = proxy(IMaterializationMapper.class, (method, args) -> switch (method) {
            case "selectUploadForMaterialization" -> upload;
            case "insertContentBlob" -> 0;
            case "selectContentBlobForUpdate" -> blob;
            case "selectReusableVersionForUpdate" -> other;
            case "selectTargetVersionForUpdate" -> null;
            case "selectActiveMaterialForUpdate" -> target;
            case "selectNextVersionNo" -> 3;
            case "insertVersion", "insertRevision", "updateLatestVersion", "insertScopeLink",
                    "completeWithoutPromotion" -> 1;
            default -> throw new UnsupportedOperationException(method);
        });
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                queued.set((ProcessingJobPO) args[0]);
                return 1;
            }
            throw new UnsupportedOperationException(method);
        });
        var adapter = new MySqlMaterializationWorkAdapter(mapper, jobs);
        ProcessingJob promotion = ProcessingJob.enqueue("job_promote", ProcessingJobTarget.forUpload("upl_1"),
                ProcessingJobStage.PROMOTE_ORIGINAL, "root", "b".repeat(64), 0, NOW);
        ProcessingJob extraction = ProcessingJob.enqueue("job_extract", ProcessingJobTarget.forRevision("rev_new"),
                ProcessingJobStage.EXTRACT_NATIVE, "root", "c".repeat(64), 0, NOW);

        MaterializationResult result = adapter.resolveAndMaterialize("upl_1",
                new MaterializationIds("mat_unused", "ver_new", "blob_unused", "rev_new", "scope_2"),
                PROFILE, promotion, extraction,
                new WorkerFence("job_resolve", "worker-1", 1), NOW);

        assertEquals(MaterializationResult.Outcome.EXTRACTION_QUEUED, result.outcome());
        assertEquals("mat_target", result.materialId());
        assertEquals("ver_new", result.versionId());
        assertEquals(ProcessingJobStage.EXTRACT_NATIVE.name(), queued.get().getStage());
    }

    @Test
    void promotionCommitRequiresTheImmutableBlobPinAndProcessableRevision() {
        IMaterializationMapper mapper = proxy(IMaterializationMapper.class, (method, args) -> switch (method) {
            case "pinBlobIfPromoting", "markVersionProcessing" -> 1;
            case "countFixedBlobForPromotion", "countProcessableVersionForPromotion",
                    "completePromotedUpload" -> 1;
            default -> throw new UnsupportedOperationException(method);
        });
        AtomicReference<ProcessingJobPO> queued = new AtomicReference<>();
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                queued.set((ProcessingJobPO) args[0]);
                return 1;
            }
            throw new UnsupportedOperationException(method);
        });
        var adapter = new MySqlMaterializationWorkAdapter(mapper, jobs);

        boolean committed = adapter.commitPromotion(promotionWork(), promoted(), extractionJob(),
                new WorkerFence("job_promote", "worker-1", 2));

        assertTrue(committed);
        assertEquals(ProcessingJobStage.EXTRACT_NATIVE.name(), queued.get().getStage());
    }

    @Test
    void promotionCommitRejectsAFormalVersionThatDoesNotMatchTheFirstWinner() {
        IMaterializationMapper mapper = proxy(IMaterializationMapper.class, (method, args) -> switch (method) {
            case "pinBlobIfPromoting", "countFixedBlobForPromotion" -> 0;
            default -> throw new UnsupportedOperationException(method);
        });
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class,
                (method, args) -> { throw new AssertionError("extraction must not be queued"); });
        var adapter = new MySqlMaterializationWorkAdapter(mapper, jobs);

        assertThrows(IllegalStateException.class, () -> adapter.commitPromotion(
                promotionWork(), promoted(), extractionJob(),
                new WorkerFence("job_promote", "worker-1", 2)));
    }

    @Test
    void staleFinalFenceThrowsSoThePromotionTransactionRollsBack() {
        IMaterializationMapper mapper = proxy(IMaterializationMapper.class, (method, args) -> switch (method) {
            case "pinBlobIfPromoting", "markVersionProcessing",
                    "countFixedBlobForPromotion", "countProcessableVersionForPromotion" -> 1;
            case "completePromotedUpload" -> 0;
            default -> throw new UnsupportedOperationException(method);
        });
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class,
                (method, args) -> { throw new AssertionError("extraction must not be queued"); });
        var adapter = new MySqlMaterializationWorkAdapter(mapper, jobs);

        assertThrows(IllegalStateException.class, () -> adapter.commitPromotion(
                promotionWork(), promoted(), extractionJob(),
                new WorkerFence("job_promote", "worker-1", 2)));
    }

    private static OriginalPromotionWork promotionWork() {
        return new OriginalPromotionWork("upl_1", 3, "quarantine", "incoming/opaque", "source-version",
                "original/blob_1/" + "a".repeat(64), "blob_1", "mat_1", "ver_1", "rev_1",
                "application/pdf", 42, "a".repeat(64), PROFILE.fingerprint(), null);
    }

    private static PromotedOriginal promoted() {
        return new PromotedOriginal("original/blob_1/" + "a".repeat(64),
                "formal-version", "etag", "checksum", 42);
    }

    private static ProcessingJob extractionJob() {
        return ProcessingJob.enqueue("job_extract", ProcessingJobTarget.forRevision("rev_1"),
                ProcessingJobStage.EXTRACT_NATIVE, "root",
                org.zipp.ai.domain.ingestion.service.ProcessingStageFingerprintPolicy.extractionInput(
                        "a".repeat(64), PROFILE.fingerprint()), 0, NOW);
    }

    private static UploadSessionPO validatedUpload() {
        UploadSessionPO upload = new UploadSessionPO();
        upload.setId("upl_1");
        upload.setOwnerType("USER");
        upload.setOwnerKey("usr_1");
        upload.setDisplayName("Agile Guide");
        upload.setTargetScopeType("CONVERSATION");
        upload.setTargetScopeKey("conv_1");
        upload.setTargetRetentionClass("TEMPORARY");
        upload.setDeclaredMime("application/pdf");
        upload.setDetectedMime("application/pdf");
        upload.setActualSha256("a".repeat(64));
        upload.setActualSize(42L);
        upload.setPageCount(1);
        upload.setState("PROCESSING");
        upload.setSecurityStatus("VALIDATED");
        upload.setGeneration(2);
        return upload;
    }

    private static ContentBlobPO availableBlob() {
        ContentBlobPO blob = new ContentBlobPO();
        blob.setId("blob_existing");
        blob.setOwnerType("USER");
        blob.setOwnerKey("usr_1");
        blob.setContentSha256("a".repeat(64));
        blob.setByteSize(42);
        blob.setDetectedMime("application/pdf");
        blob.setOriginalObjectKey("original/blob_existing/" + "a".repeat(64));
        blob.setStatus("AVAILABLE");
        return blob;
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
