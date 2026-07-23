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
import org.zipp.ai.infrastructure.dao.material.po.EvidenceUnitPO;
import org.zipp.ai.infrastructure.dao.material.po.EvidenceRegionPO;
import org.zipp.ai.infrastructure.dao.material.po.EvidenceRelationPO;

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
        po.setExcludedPagesJson("[2]");
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
        assertEquals(java.util.Set.of(2), result.orElseThrow().excludedPages());
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

    @Test
    void evidenceCommitPinsSourcesRegionsRelationsAndQueuesRetrievalWork() {
        AtomicReference<DocumentStructureArtifactPO> persistedManifest = new AtomicReference<>();
        AtomicReference<EvidenceUnitPO> persistedUnit = new AtomicReference<>();
        AtomicReference<EvidenceRegionPO> persistedRegion = new AtomicReference<>();
        AtomicReference<EvidenceRelationPO> persistedRelation = new AtomicReference<>();
        AtomicReference<MaterialSectionPO> persistedSection = new AtomicReference<>();
        AtomicReference<ProcessingJobPO> queued = new AtomicReference<>();
        MaterialSectionPO section = new MaterialSectionPO();
        section.setId("sec_1");
        section.setRevisionId("rev_1");
        persistedSection.set(section);
        // Capture insert arguments before returning the values used by immutable identity checks.
        IDocumentProcessingMapper mapper = proxy(IDocumentProcessingMapper.class, new Call() {
            @Override public Object invoke(String method, Object[] args) {
                return switch (method) {
                    case "countCurrentFence", "advanceRevision" -> 1;
                    case "insertRevisionArtifact" -> { persistedManifest.set((DocumentStructureArtifactPO) args[0]); yield 1; }
                    case "selectRevisionArtifact" -> persistedManifest.get();
                    case "insertEvidenceUnit" -> { persistedUnit.set((EvidenceUnitPO) args[0]); yield 1; }
                    case "selectEvidenceUnit" -> unitWithMysqlNumericNormalization(persistedUnit.get());
                    case "insertEvidenceRegion" -> { persistedRegion.set((EvidenceRegionPO) args[0]); yield 1; }
                    case "selectEvidenceRegion" -> regionWithMysqlJsonKeyOrder(persistedRegion.get());
                    case "insertEvidenceRelation" -> { persistedRelation.set((EvidenceRelationPO) args[0]); yield 1; }
                    case "selectEvidenceRelation" -> persistedRelation.get();
                    case "updateSectionHeading" -> {
                        persistedSection.get().setHeadingEvidenceId((String) args[2]);
                        yield 1;
                    }
                    case "selectSectionById" -> persistedSection.get();
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
        RevisionEvidenceWork source = new RevisionEvidenceWork("rev_1", "ver_1", 6, 7, "d".repeat(64),
                artifact("structure.json.gz", "structure-version"),
                artifact("visual-manifest.json.gz", "visual-manifest-version"),
                List.of(new RevisionCanonicalPageWork("page_1", 1,
                        artifact("page.png", "image-version"),
                        artifact("canonical.json.gz", "canonical-version"))));
        EvidenceRegion textRegion = new EvidenceRegion("page_1", 1,
                new NormalizedBoundingBox(0.1, 0.1, 0.9, 0.2), 0, 7, "heading");
        EvidenceUnit heading = new EvidenceUnit("evi_heading", "page_1", 1, "sec_1",
                EvidenceUnitType.HEADING, EvidenceModality.TEXT, "NATIVE", "Heading", "a".repeat(64),
                artifact("canonical.json.gz", "canonical-version"), null, List.of(textRegion),
                0.9408207900000001);
        EvidenceRegion visualRegion = new EvidenceRegion("page_1", 1,
                new NormalizedBoundingBox(0.1, 0.3, 0.9, 0.8), null, null, "vis_1");
        EvidenceUnit visual = new EvidenceUnit("evi_visual", "page_1", 1, "sec_1",
                EvidenceUnitType.VISUAL, EvidenceModality.VISUAL, "VISUAL", null, null,
                null, artifact("visual.png", "visual-version"), List.of(visualRegion), 1.0);
        EvidenceManifest manifest = new EvidenceManifest("evidence-manifest-v1", "rev_1", "ver_1",
                "e".repeat(64), "builder-v1", "c".repeat(64), List.of(heading, visual),
                List.of(new EvidenceRelation("evi_heading", "evi_visual", EvidenceRelationType.CAPTION_OF, 1.0)),
                List.of(new SectionHeadingEvidence("sec_1", "evi_heading", "heading")));
        StoredArtifact manifestArtifact = new StoredArtifact("evidence-manifest.json.gz", "evidence-version",
                "f".repeat(64), 30, "application/json+gzip");
        ProcessingJob successor = ProcessingJob.enqueue("job_retrieval", ProcessingJobTarget.forRevision("rev_1"),
                ProcessingJobStage.BUILD_RETRIEVAL_CHUNKS, "root",
                org.zipp.ai.domain.ingestion.service.ProcessingStageFingerprintPolicy.retrievalInput(
                        manifestArtifact.contentSha256(), source.processingFingerprint()),
                0, Instant.parse("2026-07-26T00:00:00Z"));

        assertTrue(adapter.commitEvidence(source, new EvidenceBuildResult(manifest, manifestArtifact),
                successor, new WorkerFence("job_1", "worker-1", 2)));

        assertEquals("visual-version", persistedUnit.get().getVisualObjectVersionId());
        assertEquals("CAPTION_OF", persistedRelation.get().getRelationType());
        assertEquals("evi_heading", persistedSection.get().getHeadingEvidenceId());
        assertEquals(ProcessingJobStage.BUILD_RETRIEVAL_CHUNKS.name(), queued.get().getStage());
    }

    private static StoredArtifact artifact(String key, String version) {
        return new StoredArtifact(key, version, "a".repeat(64), 10, "application/octet-stream");
    }

    private static EvidenceUnitPO unitWithMysqlNumericNormalization(EvidenceUnitPO source) {
        EvidenceUnitPO row = new EvidenceUnitPO();
        row.setId(source.getId());
        row.setVersionId(source.getVersionId());
        row.setRevisionId(source.getRevisionId());
        row.setPageId(source.getPageId());
        row.setSectionId(source.getSectionId());
        row.setUnitType(source.getUnitType());
        row.setModality(source.getModality());
        row.setSourceChannel(source.getSourceChannel());
        row.setDisplayTextObjectKey(source.getDisplayTextObjectKey());
        row.setDisplayTextObjectVersionId(source.getDisplayTextObjectVersionId());
        row.setVisualObjectKey(source.getVisualObjectKey());
        row.setVisualObjectVersionId(source.getVisualObjectVersionId());
        row.setDisplayTextSha256(source.getDisplayTextSha256());
        // Match MySQL JSON's valid normalization of a whole-valued floating-point number.
        row.setQualityJson(source.getQualityJson()
                .replace("0.9408207900000001", "0.94082079")
                .replace("1.0", "1"));
        row.setStatus(source.getStatus());
        return row;
    }

    private static EvidenceRegionPO regionWithMysqlJsonKeyOrder(EvidenceRegionPO source) {
        EvidenceRegionPO row = new EvidenceRegionPO();
        row.setEvidenceId(source.getEvidenceId());
        row.setPageId(source.getPageId());
        row.setOrdinal(source.getOrdinal());
        // MySQL JSON does not promise to preserve the insertion order of object keys.
        String y1 = "heading".equals(source.getSourceBlockRef()) ? "0.1" : "0.3";
        String y2 = "heading".equals(source.getSourceBlockRef()) ? "0.2" : "0.8";
        row.setBboxJson("{\"y2\":" + y2 + ",\"x2\":0.9,\"y1\":" + y1 + ",\"x1\":0.1}");
        row.setDisplayCharStart(source.getDisplayCharStart());
        row.setDisplayCharEnd(source.getDisplayCharEnd());
        row.setSourceBlockRef(source.getSourceBlockRef());
        return row;
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
