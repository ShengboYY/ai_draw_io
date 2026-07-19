package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.*;
import org.zipp.ai.infrastructure.dao.material.IDocumentProcessingMapper;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.po.DocumentExtractionWorkPO;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingJobPO;
import org.zipp.ai.infrastructure.dao.material.po.RevisionArtifactPO;
import org.zipp.ai.infrastructure.dao.material.po.RevisionStructurePagePO;
import org.zipp.ai.infrastructure.dao.material.po.DocumentStructureArtifactPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialSectionPO;
import org.zipp.ai.infrastructure.dao.material.po.VisualCropArtifactPO;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlDocumentProcessingWorkAdapterTest {

    @Test
    void readsAnExactFormalObjectPinForExtraction() {
        DocumentExtractionWorkPO po = new DocumentExtractionWorkPO();
        po.setRevisionId("rev_1");
        po.setDetectedMediaType("application/pdf");
        po.setRevisionFenceGeneration(4);
        po.setMaterialLifecycleGeneration(9);
        po.setProcessingFingerprint("d".repeat(64));
        po.setObjectKey("original/blob_1/hash");
        po.setObjectVersionId("version-7");
        po.setContentSha256("a".repeat(64));
        po.setByteSize(42);
        po.setContentType("application/pdf");
        IDocumentProcessingMapper mapper = proxy(IDocumentProcessingMapper.class,
                (method, args) -> "selectExtractionWork".equals(method) ? po
                        : unsupported(method));
        var adapter = new MySqlDocumentProcessingWorkAdapter(mapper, unusedJobs());

        Optional<RevisionExtractionWork> result = adapter.findExtractionWork("rev_1",
                new WorkerFence("job_1", "worker-1", 2));

        assertEquals("version-7", result.orElseThrow().original().objectVersionId());
        assertEquals(4, result.orElseThrow().revisionFenceGeneration());
        assertEquals("d".repeat(64), result.orElseThrow().processingFingerprint());
    }

    @Test
    void staleRevisionGenerationCannotPersistPagesOrQueueTheNextStage() {
        IDocumentProcessingMapper mapper = proxy(IDocumentProcessingMapper.class, (method, args) -> {
            if ("countCurrentFence".equals(method)) {
                return 0;
            }
            throw new AssertionError("stale fence must fail before " + method);
        });
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class,
                (method, args) -> { throw new AssertionError("next job must not be queued"); });
        var adapter = new MySqlDocumentProcessingWorkAdapter(mapper, jobs);
        RevisionExtractionWork source = new RevisionExtractionWork("rev_1", "application/pdf", 3, 8,
                "d".repeat(64),
                artifact("original", "original-version"));
        NativePageResult page = new NativePageResult(1, 100, 200, true,
                artifact("page.png", "image-version"), artifact("native.json.gz", "native-version"), null);

        assertFalse(adapter.commitNativeExtraction(source, List.of(page), List.of(nextJob()),
                new WorkerFence("job_1", "worker-1", 2)));
    }

    @Test
    void nativeCommitPinsArtifactsAndAtomicallyQueuesOcr() {
        AtomicReference<RevisionArtifactPO> lastArtifact = new AtomicReference<>();
        AtomicReference<ProcessingJobPO> queued = new AtomicReference<>();
        // Capture each inserted immutable artifact so the adapter can verify the identity it just persisted.
        AtomicReference<org.zipp.ai.infrastructure.dao.material.po.MaterialPagePO> persistedPage =
                new AtomicReference<>();
        IDocumentProcessingMapper mapper = proxy(IDocumentProcessingMapper.class, new Call() {
            @Override public Object invoke(String method, Object[] args) {
                return switch (method) {
                    case "countCurrentFence", "updatePageCount", "advanceRevision" -> 1;
                    case "insertPage" -> {
                        persistedPage.set((org.zipp.ai.infrastructure.dao.material.po.MaterialPagePO) args[0]);
                        yield 1;
                    }
                    case "selectPage" -> persistedPage.get();
                    case "insertArtifact" -> {
                        lastArtifact.set((RevisionArtifactPO) args[0]);
                        yield 1;
                    }
                    case "selectArtifact" -> lastArtifact.get();
                    default -> unsupported(method);
                };
            }
        });
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                queued.set((ProcessingJobPO) args[0]);
                return 1;
            }
            return unsupported(method);
        });
        var adapter = new MySqlDocumentProcessingWorkAdapter(mapper, jobs);
        RevisionExtractionWork source = new RevisionExtractionWork("rev_1", "application/pdf", 3, 8,
                "d".repeat(64),
                artifact("original", "original-version"));
        NativePageResult page = new NativePageResult(1, 100, 200, true,
                artifact("page.png", "image-version"), artifact("native.json.gz", "native-version"), null);

        adapter.commitNativeExtraction(source, List.of(page), List.of(nextJob()),
                new WorkerFence("job_1", "worker-1", 2));

        assertEquals(ProcessingJobStage.OCR_SELECTED_PAGES.name(), queued.get().getStage());
        assertEquals("NATIVE_EXTRACTION", lastArtifact.get().getArtifactKind());
    }

    @Test
    void structureWorkReadsExactCanonicalAndPageImageVersions() {
        RevisionStructurePagePO row = new RevisionStructurePagePO();
        row.setRevisionId("rev_1");
        row.setVersionId("ver_1");
        row.setRevisionFenceGeneration(4);
        row.setMaterialLifecycleGeneration(7);
        row.setProcessingFingerprint("d".repeat(64));
        row.setPageId("page_1");
        row.setPageNo(1);
        row.setPageImageKey("page.png");
        row.setPageImageVersionId("image-version");
        row.setPageImageSha256("a".repeat(64));
        row.setPageImageSize(10);
        row.setPageImageContentType("image/png");
        row.setCanonicalKey("canonical.json.gz");
        row.setCanonicalVersionId("canonical-version");
        row.setCanonicalSha256("b".repeat(64));
        row.setCanonicalSize(20);
        row.setCanonicalContentType("application/json+gzip");
        IDocumentProcessingMapper mapper = proxy(IDocumentProcessingMapper.class,
                (method, args) -> "selectStructureWork".equals(method) ? List.of(row) : unsupported(method));
        var adapter = new MySqlDocumentProcessingWorkAdapter(mapper, unusedJobs());

        RevisionStructureWork work = adapter.findStructureWork("rev_1",
                new WorkerFence("job_1", "worker-1", 2)).orElseThrow();

        assertEquals("image-version", work.pages().get(0).pageImage().objectVersionId());
        assertEquals("canonical-version", work.pages().get(0).canonicalPage().objectVersionId());
    }

    @Test
    void structureCommitPinsManifestAndSectionsBeforeQueuingVisualWork() {
        AtomicReference<DocumentStructureArtifactPO> persistedArtifact = new AtomicReference<>();
        AtomicReference<MaterialSectionPO> persistedSection = new AtomicReference<>();
        AtomicReference<ProcessingJobPO> queued = new AtomicReference<>();
        IDocumentProcessingMapper mapper = proxy(IDocumentProcessingMapper.class, (method, args) -> switch (method) {
            case "countCurrentFence", "advanceRevision" -> 1;
            case "insertRevisionArtifact" -> {
                persistedArtifact.set((DocumentStructureArtifactPO) args[0]);
                yield 1;
            }
            case "selectRevisionArtifact" -> persistedArtifact.get();
            case "insertSection" -> {
                persistedSection.set((MaterialSectionPO) args[0]);
                yield 1;
            }
            case "selectSection" -> persistedSection.get();
            default -> unsupported(method);
        });
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                queued.set((ProcessingJobPO) args[0]);
                return 1;
            }
            return unsupported(method);
        });
        var adapter = new MySqlDocumentProcessingWorkAdapter(mapper, jobs);
        RevisionStructureWork source = new RevisionStructureWork("rev_1", "ver_1", 4, 7,
                "d".repeat(64), List.of(new RevisionCanonicalPageWork("page_1", 1,
                artifact("page.png", "image-version"), artifact("canonical.json.gz", "canonical-version"))));
        DocumentSection section = new DocumentSection("sec_1", null, 1, 1, 1, 1, null, "c".repeat(64));
        DocumentStructure structure = new DocumentStructure("document-structure-v1", List.of(section),
                List.of(), List.of(), "e".repeat(64));
        StoredArtifact structureArtifact = new StoredArtifact("structure.json.gz", "structure-version",
                "f".repeat(64), 30, "application/json+gzip");
        ProcessingJob successor = ProcessingJob.enqueue("job_visual", ProcessingJobTarget.forRevision("rev_1"),
                ProcessingJobStage.ANALYZE_VISUALS, "root",
                org.zipp.ai.domain.ingestion.service.ProcessingStageFingerprintPolicy.visualInput(
                        structure.structureHash(), structureArtifact.contentSha256(), source.processingFingerprint()),
                0, Instant.parse("2026-07-23T00:00:00Z"));

        assertTrue(adapter.commitStructure(source, new DocumentStructureResult(structure, structureArtifact),
                successor, new WorkerFence("job_1", "worker-1", 2)));

        assertEquals("structure-version", persistedArtifact.get().getObjectVersionId());
        assertEquals("sec_1", persistedSection.get().getId());
        assertEquals(ProcessingJobStage.ANALYZE_VISUALS.name(), queued.get().getStage());
    }

    @Test
    void structureCommitRejectsAConflictingArtifactContentType() {
        DocumentStructureArtifactPO persisted = new DocumentStructureArtifactPO();
        persisted.setObjectKey("structure.json.gz");
        persisted.setObjectVersionId("structure-version");
        persisted.setContentSha256("f".repeat(64));
        persisted.setByteSize(30);
        persisted.setContentType("application/json");
        IDocumentProcessingMapper mapper = proxy(IDocumentProcessingMapper.class, (method, args) -> switch (method) {
            case "countCurrentFence", "insertRevisionArtifact" -> 1;
            case "selectRevisionArtifact" -> persisted;
            default -> unsupported(method);
        });
        var adapter = new MySqlDocumentProcessingWorkAdapter(mapper, unusedJobs());
        RevisionStructureWork source = new RevisionStructureWork("rev_1", "ver_1", 4, 7,
                "d".repeat(64), List.of(new RevisionCanonicalPageWork("page_1", 1,
                artifact("page.png", "image-version"), artifact("canonical.json.gz", "canonical-version"))));
        DocumentStructure structure = new DocumentStructure("document-structure-v1",
                List.of(new DocumentSection("sec_1", null, 1, 1, 1, 1, null, "c".repeat(64))),
                List.of(), List.of(), "e".repeat(64));
        StoredArtifact structureArtifact = new StoredArtifact("structure.json.gz", "structure-version",
                "f".repeat(64), 30, "application/json+gzip");
        ProcessingJob successor = ProcessingJob.enqueue("job_visual", ProcessingJobTarget.forRevision("rev_1"),
                ProcessingJobStage.ANALYZE_VISUALS, "root",
                org.zipp.ai.domain.ingestion.service.ProcessingStageFingerprintPolicy.visualInput(
                        structure.structureHash(), structureArtifact.contentSha256(), source.processingFingerprint()),
                0, Instant.parse("2026-07-23T00:00:00Z"));

        assertThrows(IllegalStateException.class, () -> adapter.commitStructure(source,
                new DocumentStructureResult(structure, structureArtifact), successor,
                new WorkerFence("job_1", "worker-1", 2)));
    }

    @Test
    void visualCommitPinsCropsAndManifestBeforeQueuingEvidenceWork() {
        AtomicReference<VisualCropArtifactPO> persistedCrop = new AtomicReference<>();
        AtomicReference<DocumentStructureArtifactPO> persistedManifest = new AtomicReference<>();
        AtomicReference<ProcessingJobPO> queued = new AtomicReference<>();
        IDocumentProcessingMapper mapper = proxy(IDocumentProcessingMapper.class, (method, args) -> switch (method) {
            case "countCurrentFence", "updatePageVisualStatus", "advanceRevision" -> 1;
            case "insertVisualCrop" -> {
                persistedCrop.set((VisualCropArtifactPO) args[0]);
                yield 1;
            }
            case "selectVisualCrop" -> persistedCrop.get();
            case "insertRevisionArtifact" -> {
                persistedManifest.set((DocumentStructureArtifactPO) args[0]);
                yield 1;
            }
            case "selectRevisionArtifact" -> persistedManifest.get();
            default -> unsupported(method);
        });
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                queued.set((ProcessingJobPO) args[0]);
                return 1;
            }
            return unsupported(method);
        });
        var adapter = new MySqlDocumentProcessingWorkAdapter(mapper, jobs);
        RevisionCanonicalPageWork page = new RevisionCanonicalPageWork("page_1", 1,
                artifact("page.png", "image-version"), artifact("canonical.json.gz", "canonical-version"));
        RevisionVisualWork source = new RevisionVisualWork("rev_1", "ver_1", 5, 7, "d".repeat(64),
                artifact("structure.json.gz", "structure-version"), List.of(page));
        VisualCandidate candidate = new VisualCandidate("vis_1", 1,
                List.of(new NormalizedBoundingBox(0.1, 0.1, 0.5, 0.5)), "caption_1");
        VisualCropArtifact crop = new VisualCropArtifact("page_1", candidate,
                artifact("visual/vis_1.png", "crop-version"));
        VisualCropManifest manifest = new VisualCropManifest("visual-crop-manifest-v1", "e".repeat(64),
                "policy-v1", 1, 0, List.of(crop));
        StoredArtifact manifestArtifact = new StoredArtifact("visual-manifest.json.gz", "manifest-version",
                "f".repeat(64), 30, "application/json+gzip");
        ProcessingJob successor = ProcessingJob.enqueue("job_evidence", ProcessingJobTarget.forRevision("rev_1"),
                ProcessingJobStage.BUILD_EVIDENCE_UNITS, "root",
                org.zipp.ai.domain.ingestion.service.ProcessingStageFingerprintPolicy.evidenceInput(
                        manifestArtifact.contentSha256(), source.processingFingerprint()),
                0, Instant.parse("2026-07-25T00:00:00Z"));

        assertTrue(adapter.commitVisualCrops(source, new VisualProcessingResult(manifest, manifestArtifact),
                successor, new WorkerFence("job_1", "worker-1", 2)));

        assertEquals("crop-version", persistedCrop.get().getObjectVersionId());
        assertEquals("VISUAL_CROP_MANIFEST", persistedManifest.get().getArtifactKind());
        assertEquals(ProcessingJobStage.BUILD_EVIDENCE_UNITS.name(), queued.get().getStage());
    }

    private static StoredArtifact artifact(String key, String version) {
        return new StoredArtifact(key, version, "a".repeat(64), 10, "application/octet-stream");
    }

    private static ProcessingJob nextJob() {
        return ProcessingJob.enqueue("job_ocr", ProcessingJobTarget.forRevision("rev_1"),
                ProcessingJobStage.OCR_SELECTED_PAGES, "page:1", "b".repeat(64), 0,
                Instant.parse("2026-07-22T00:00:00Z"));
    }

    private static IProcessingJobMapper unusedJobs() {
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
