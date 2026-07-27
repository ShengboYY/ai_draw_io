package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.*;
import org.zipp.ai.domain.retrieval.model.valobj.*;
import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;
import org.zipp.ai.infrastructure.dao.material.IDocumentProcessingMapper;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.IVectorProjectionMapper;
import org.zipp.ai.infrastructure.dao.material.po.*;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlVectorProjectionWorkAdapterTest {

    @Test
    void finalUpsertLocksRevisionGateBeforeAtomicallyQueuingVerifier() {
        List<String> calls = new ArrayList<>();
        IVectorProjectionMapper mapper = proxy(IVectorProjectionMapper.class, (method, args) -> {
            calls.add(method);
            return switch (method) {
                case "lockRevisionGate" -> "rev_1";
                case "updateProjectionBatchState", "updateBatchState" -> 1;
                case "countIncompleteBatches" -> 0;
                default -> unsupported(method);
            };
        });
        IDocumentProcessingMapper documents = proxy(IDocumentProcessingMapper.class,
                (method, args) -> "countCurrentFence".equals(method) ? 1 : unsupported(method));
        AtomicReference<ProcessingJobPO> queued = new AtomicReference<>();
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                queued.set((ProcessingJobPO) args[0]);
                return 1;
            }
            return unsupported(method);
        });
        MySqlVectorProjectionWorkAdapter adapter = new MySqlVectorProjectionWorkAdapter(
                mapper, documents, jobs);
        VectorUpsertWork source = upsertWork();
        VectorBatchPayload payload = new VectorBatchPayload("vector-batch-v1", "rev_1",
                source.profile().generationId(), 0, "b".repeat(64),
                List.of(new VectorEmbeddingValue("chunk_1", "vector_1", "c".repeat(64), new float[4])));
        ProcessingJob verifier = ProcessingJob.enqueue("job_verify", ProcessingJobTarget.forRevision("rev_1"),
                ProcessingJobStage.VERIFY_PROJECTION_MANIFEST,
                VectorManifestWork.verificationWorkKey(source.profile().generationId()),
                VectorManifestWork.verificationInputFingerprint("rev_1", source.profile().generationId()),
                0, Instant.parse("2026-07-28T00:00:00Z"));

        assertTrue(adapter.commitUpsert(source, payload, verifier,
                new WorkerFence("job_upsert", "worker_1", 3)));

        assertTrue(calls.indexOf("lockRevisionGate") < calls.indexOf("updateProjectionBatchState"));
        assertEquals(ProcessingJobStage.VERIFY_PROJECTION_MANIFEST.name(), queued.get().getStage());
    }

    @Test
    void manifestWorkRejectsMissingDenseEligibleProjection() {
        VectorProjectionWorkPO row = workRow();
        row.setChunkId("chunk_1");
        row.setVectorId("vector_1");
        row.setProjectionFingerprint("c".repeat(64));
        row.setEligibleProjectionCount(2);
        IVectorProjectionMapper mapper = proxy(IVectorProjectionMapper.class,
                (method, args) -> "selectManifestWork".equals(method) ? List.of(row) : unsupported(method));
        MySqlVectorProjectionWorkAdapter adapter = new MySqlVectorProjectionWorkAdapter(
                mapper, unusedDocuments(), unusedJobs());

        assertThrows(IllegalStateException.class, () -> adapter.findManifestWork(
                "rev_1", VectorManifestWork.verificationWorkKey(profile().generationId()),
                new WorkerFence("job_verify", "worker_1", 3)));
    }

    @Test
    void publicationAtomicallyActivatesInitialGenerationThenRevisionAndVersion() {
        List<String> calls = new ArrayList<>();
        VectorProjectionWorkPO row = publicationRow();
        IVectorProjectionMapper mapper = proxy(IVectorProjectionMapper.class, (method, args) -> {
            calls.add(method);
            return switch (method) {
                case "lockMaterialLifecycleState" -> "ACTIVE";
                case "lockRevisionGate" -> "rev_1";
                case "lockGenerationState" -> IndexGenerationState.BUILDING.name();
                case "selectActiveGenerationForUpdate" -> null;
                case "selectPublicationWork" -> List.of(row);
                case "activateInitialGeneration", "publishRevision", "activateVersionRevision" -> 1;
                case "recordInitialProcessingUsage" -> 1;
                default -> unsupported(method);
            };
        });
        IDocumentProcessingMapper documents = proxy(IDocumentProcessingMapper.class,
                (method, args) -> "countCurrentFence".equals(method) ? 1 : unsupported(method));
        MySqlVectorProjectionWorkAdapter adapter = new MySqlVectorProjectionWorkAdapter(
                mapper, documents, unusedJobs());

        assertTrue(adapter.commitPublication(publicationWork(),
                new WorkerFence("job_publish", "worker_1", 3)));

        assertTrue(calls.indexOf("lockMaterialLifecycleState") < calls.indexOf("lockRevisionGate"));
        assertTrue(calls.indexOf("activateInitialGeneration") < calls.indexOf("publishRevision"));
        assertTrue(calls.indexOf("publishRevision") < calls.indexOf("activateVersionRevision"));
        assertTrue(calls.indexOf("activateVersionRevision") < calls.indexOf("recordInitialProcessingUsage"));
    }

    @Test
    void synchronizationRegistersTargetsBeforeGenerationRoutedCompatibilityJobs() {
        List<String> calls = new ArrayList<>();
        AtomicInteger statusReads = new AtomicInteger();
        VectorProjectionWorkPO pending = workRow();
        pending.setProjectionRole(VectorProjectionRole.COMPATIBILITY.name());
        IVectorProjectionMapper mapper = proxy(IVectorProjectionMapper.class, (method, args) -> {
            calls.add(method);
            return switch (method) {
                case "insertGeneration", "insertCompatibilityProfile",
                        "advanceCompatibilityTargetGeneration" -> 1;
                case "selectGeneration" -> generation(IndexGenerationState.BUILDING, profile().generationId());
                case "selectGenerationBackfillStatus" -> statusReads.getAndIncrement() == 0
                        ? status(IndexGenerationState.BUILDING, 0, 0, 0)
                        : status(IndexGenerationState.BUILDING, 1, 1, 0);
                case "selectCompatibilityTokenizer" -> profile().tokenizerFingerprint();
                case "insertRequiredGenerationTargets" -> 1;
                case "selectPendingGenerationTargets" -> List.of(pending);
                default -> unsupported(method);
            };
        });
        AtomicReference<ProcessingJobPO> queued = new AtomicReference<>();
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                queued.set((ProcessingJobPO) args[0]);
                return 1;
            }
            return unsupported(method);
        });
        MySqlIndexGenerationCompatibilityAdapter adapter =
                new MySqlIndexGenerationCompatibilityAdapter(mapper, jobs);

        adapter.synchronize(profile(), 10, Instant.parse("2026-07-20T00:00:00Z"));

        assertTrue(calls.indexOf("insertRequiredGenerationTargets")
                < calls.indexOf("selectPendingGenerationTargets"));
        assertEquals(ProcessingJobStage.BUILD_COMPATIBILITY_PROJECTION.name(), queued.get().getStage());
        assertEquals(CompatibilityProjectionWork.workKey(profile().generationId()), queued.get().getWorkKey());
    }

    @Test
    void synchronizationDoesNotInventAnActiveGenerationDuringBootstrap() {
        AtomicInteger targetScans = new AtomicInteger();
        IVectorProjectionMapper mapper = proxy(IVectorProjectionMapper.class, (method, args) -> switch (method) {
            case "insertGeneration", "insertCompatibilityProfile" -> 1;
            case "selectGeneration" -> generation(IndexGenerationState.BUILDING, profile().generationId());
            case "selectGenerationBackfillStatus" -> {
                GenerationBackfillStatusPO status = status(IndexGenerationState.BUILDING, 0, 0, 0);
                status.setActiveGenerationId(null);
                yield status;
            }
            case "selectCompatibilityTokenizer" -> profile().tokenizerFingerprint();
            case "insertRequiredGenerationTargets" -> {
                targetScans.incrementAndGet();
                yield 0;
            }
            case "selectPendingGenerationTargets" -> List.of();
            default -> unsupported(method);
        });

        GenerationBackfillStatus result = new MySqlIndexGenerationCompatibilityAdapter(
                mapper, unusedJobs()).synchronize(profile(), 10,
                Instant.parse("2026-07-20T00:00:00Z"));

        assertEquals(null, result.activeGenerationId());
        assertEquals(1, targetScans.get());
    }

    @Test
    void activationRetiresTheBaselineBeforeActivatingThePinnedShadowGeneration() {
        List<String> calls = new ArrayList<>();
        GenerationBackfillStatus expected = new GenerationBackfillStatus(profile().generationId(),
                IndexGenerationState.SHADOW, "ig_old", 2, 2, 2, 5, 5, 2);
        GenerationShadowReport report = new GenerationShadowReport(
                "generation-shadow-report-v1", "report_1", profile().generationId(), "ig_old", 2,
                "a".repeat(64), 120, 0, 0.91, 0.90, 0.82, 0.80,
                110, 100, Instant.parse("2026-07-20T00:00:00Z"));
        IVectorProjectionMapper mapper = proxy(IVectorProjectionMapper.class, (method, args) -> {
            calls.add(method);
            return switch (method) {
                case "selectActiveGenerationForUpdate" -> "ig_old";
                case "selectGenerationForUpdate" -> generation(IndexGenerationState.SHADOW,
                        profile().generationId());
                case "selectCompatibilityTokenizer" -> profile().tokenizerFingerprint();
                case "insertRequiredGenerationTargets" -> 0;
                case "lockGenerationTargets" -> List.of("rev_1", "rev_2");
                case "selectGenerationBackfillStatus" -> status(IndexGenerationState.SHADOW, 2, 2, 2);
                case "selectShadowReport" -> shadowReport(report);
                case "retireActiveGeneration", "activateShadowGeneration" -> 1;
                case "selectPendingGenerationPublications" -> List.of();
                default -> unsupported(method);
            };
        });
        MySqlIndexGenerationCompatibilityAdapter adapter =
                new MySqlIndexGenerationCompatibilityAdapter(mapper, unusedJobs());

        assertTrue(adapter.activate(expected, report,
                Instant.parse("2026-07-20T01:00:00Z"), Instant.parse("2026-07-27T01:00:00Z")));

        assertTrue(calls.indexOf("lockGenerationTargets")
                < calls.indexOf("selectGenerationBackfillStatus"));
        assertTrue(calls.indexOf("retireActiveGeneration") < calls.indexOf("activateShadowGeneration"));
    }

    @Test
    void activeGenerationResumesInFlightRevisionPublicationOnItsCompatibilityManifest() {
        VectorProjectionWorkPO row = new VectorProjectionWorkPO();
        row.setRevisionId("rev_in_flight");
        row.setProjectionManifestSha256("f".repeat(64));
        row.setProjectionManifestHash("b".repeat(64));
        IVectorProjectionMapper mapper = proxy(IVectorProjectionMapper.class, (method, args) -> switch (method) {
            case "insertGeneration" -> 1;
            case "selectGeneration" -> generation(IndexGenerationState.ACTIVE, profile().generationId());
            case "selectGenerationBackfillStatus" -> {
                GenerationBackfillStatusPO status = status(IndexGenerationState.ACTIVE, 1, 1, 1);
                status.setActiveGenerationId(profile().generationId());
                yield status;
            }
            case "insertCompatibilityProfile" -> 1;
            case "selectCompatibilityTokenizer" -> profile().tokenizerFingerprint();
            case "insertRequiredGenerationTargets" -> 0;
            case "selectPendingGenerationTargets" -> List.of();
            case "selectPendingGenerationPublications" -> List.of(row);
            default -> unsupported(method);
        });
        AtomicReference<ProcessingJobPO> queued = new AtomicReference<>();
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                queued.set((ProcessingJobPO) args[0]);
                return 1;
            }
            return unsupported(method);
        });

        new MySqlIndexGenerationCompatibilityAdapter(mapper, jobs)
                .synchronize(profile(), 10, Instant.parse("2026-07-20T00:00:00Z"));

        assertEquals(ProcessingJobStage.PUBLISH_REVISION.name(), queued.get().getStage());
        assertEquals(RevisionPublicationWork.publicationWorkKey(profile().generationId()),
                queued.get().getWorkKey());
    }

    @Test
    void activeGenerationSchedulesACompatibilityBuildForANewlyDurableRevision() {
        VectorProjectionWorkPO row = workRow();
        IVectorProjectionMapper mapper = proxy(IVectorProjectionMapper.class, (method, args) -> switch (method) {
            case "insertGeneration" -> 1;
            case "selectGeneration" -> generation(IndexGenerationState.ACTIVE, profile().generationId());
            case "selectGenerationBackfillStatus" -> {
                GenerationBackfillStatusPO status = status(IndexGenerationState.ACTIVE, 1, 1, 1);
                status.setActiveGenerationId(profile().generationId());
                yield status;
            }
            case "insertCompatibilityProfile" -> 1;
            case "selectCompatibilityTokenizer" -> profile().tokenizerFingerprint();
            case "insertRequiredGenerationTargets" -> 1;
            case "advanceCompatibilityTargetGeneration" -> 1;
            case "selectPendingGenerationTargets" -> List.of(row);
            case "selectPendingGenerationPublications" -> List.of();
            default -> unsupported(method);
        });
        AtomicReference<ProcessingJobPO> queued = new AtomicReference<>();
        IProcessingJobMapper jobs = proxy(IProcessingJobMapper.class, (method, args) -> {
            if ("insert".equals(method)) {
                queued.set((ProcessingJobPO) args[0]);
                return 1;
            }
            return unsupported(method);
        });

        new MySqlIndexGenerationCompatibilityAdapter(mapper, jobs)
                .synchronize(profile(), 10, Instant.parse("2026-07-20T00:00:00Z"));

        assertEquals(ProcessingJobStage.BUILD_COMPATIBILITY_PROJECTION.name(), queued.get().getStage());
        assertEquals("ig:" + profile().generationId() + ":compatibility", queued.get().getWorkKey());
    }

    private VectorUpsertWork upsertWork() {
        RevisionProjectionContext context = new RevisionProjectionContext("rev_1", "ver_1", "mat_1",
                OwnerType.USER, "owner_1", 7, 8, "d".repeat(64), artifact("manifest"), true);
        return new VectorUpsertWork(context, profile(), 0,
                "ig:" + profile().generationId() + ":batch:0000", "b".repeat(64), artifact("vectors"),
                List.of(new VectorProjectionMetadata("chunk_1", "vector_1", "c".repeat(64),
                        RetrievalChunkType.CONTENT,
                        org.zipp.ai.domain.ingestion.model.valobj.EvidenceModality.TEXT, 1, "en")));
    }

    private VectorProjectionWorkPO workRow() {
        VectorProjectionWorkPO row = new VectorProjectionWorkPO();
        row.setRevisionId("rev_1");
        row.setVersionId("ver_1");
        row.setMaterialId("mat_1");
        row.setOwnerType(OwnerType.USER.name());
        row.setOwnerKey("owner_1");
        row.setRevisionFenceGeneration(7);
        row.setMaterialLifecycleGeneration(8);
        row.setProcessingFingerprint("d".repeat(64));
        row.setRetrievalManifestKey("manifest");
        row.setRetrievalManifestVersionId("v1");
        row.setRetrievalManifestSha256("a".repeat(64));
        row.setRetrievalManifestSize(10);
        row.setRetrievalManifestContentType("application/json+gzip");
        VectorGenerationProfile profile = profile();
        row.setIndexGenerationId(profile.generationId());
        row.setIndexName(profile.indexName());
        row.setNamespace(profile.namespace());
        row.setEmbeddingModel(profile.embeddingModel());
        row.setEmbeddingModelFingerprint(profile.embeddingModelFingerprint());
        row.setDimension(profile.dimension());
        row.setMetric(profile.metric());
        row.setVectorSchemaVersion(profile.vectorSchemaVersion());
        row.setTokenizerFingerprint(profile.tokenizerFingerprint());
        row.setProjectionRole("PRIMARY");
        return row;
    }

    private VectorProjectionWorkPO publicationRow() {
        VectorProjectionWorkPO row = workRow();
        row.setProjectionManifestKey("projection-manifest");
        row.setProjectionManifestVersionId("v3");
        row.setProjectionManifestSha256("f".repeat(64));
        row.setProjectionManifestSize(42);
        row.setProjectionManifestContentType("application/json+gzip");
        row.setProjectionManifestHash("b".repeat(64));
        row.setStructureKey("structure");
        row.setStructureVersionId("v1");
        row.setStructureSha256("a".repeat(64));
        row.setStructureSize(10);
        row.setStructureContentType("application/json+gzip");
        row.setEvidenceManifestKey("evidence");
        row.setEvidenceManifestVersionId("v1");
        row.setEvidenceManifestSha256("a".repeat(64));
        row.setEvidenceManifestSize(10);
        row.setEvidenceManifestContentType("application/json+gzip");
        row.setRetrievalChunkCount(1);
        row.setLexicalProjectionCount(1);
        row.setExactTermCount(0);
        row.setChunkEvidenceMappingCount(1);
        row.setEvidenceUnitCount(1);
        row.setExpectedProjectionCount(1);
        row.setIndexedProjectionCount(1);
        row.setGenerationState(IndexGenerationState.BUILDING.name());
        row.setChunkId("chunk_1");
        row.setVectorId("vector_1");
        row.setProjectionFingerprint("c".repeat(64));
        return row;
    }

    private RevisionPublicationWork publicationWork() {
        RevisionProjectionContext context = new RevisionProjectionContext("rev_1", "ver_1", "mat_1",
                OwnerType.USER, "owner_1", 7, 8, "d".repeat(64), artifact("manifest"), true);
        StoredArtifact projection = new StoredArtifact("projection-manifest", "v3",
                "f".repeat(64), 42, "application/json+gzip");
        return new RevisionPublicationWork(context, profile(), artifact("structure"), artifact("evidence"), null,
                projection, "b".repeat(64), 1, 1, 0, 1, 1,
                1, 1, IndexGenerationState.BUILDING, null,
                List.of(new org.zipp.ai.domain.retrieval.projection.VectorProjectionManifestEntry(
                        "chunk_1", "vector_1", "c".repeat(64))));
    }

    private VectorGenerationProfile profile() {
        return new VectorGenerationProfile("index", "namespace", "model", "e".repeat(64),
                4, "cosine", "vector-v1", "tokenizer-v1");
    }

    private RagIndexGenerationPO generation(IndexGenerationState state, String id) {
        RagIndexGenerationPO po = new RagIndexGenerationPO();
        po.setId(id);
        po.setIndexName(profile().indexName());
        po.setNamespace(profile().namespace());
        po.setEmbeddingModel(profile().embeddingModel());
        po.setEmbeddingModelFingerprint(profile().embeddingModelFingerprint());
        po.setDimension(profile().dimension());
        po.setMetric(profile().metric());
        po.setVectorSchemaVersion(profile().vectorSchemaVersion());
        po.setState(state.name());
        return po;
    }

    private GenerationBackfillStatusPO status(IndexGenerationState state, long targetGeneration,
                                               int required, int ready) {
        GenerationBackfillStatusPO po = new GenerationBackfillStatusPO();
        po.setGenerationId(profile().generationId());
        po.setGenerationState(state.name());
        po.setActiveGenerationId("ig_old");
        po.setTargetGeneration(targetGeneration);
        po.setRequiredRevisionCount(required);
        po.setReadyRevisionCount(ready);
        po.setExpectedVectorCount(required == 0 ? 0 : 5);
        po.setIndexedVectorCount(ready == required ? po.getExpectedVectorCount() : 0);
        po.setReadyManifestCount(ready);
        return po;
    }

    private GenerationShadowReportPO shadowReport(GenerationShadowReport report) {
        GenerationShadowReportPO po = new GenerationShadowReportPO();
        po.setReportId(report.reportId());
        po.setSchemaVersion(report.schemaVersion());
        po.setIndexGenerationId(report.generationId());
        po.setBaselineGenerationId(report.baselineGenerationId());
        po.setTargetGeneration(report.targetGeneration());
        po.setPolicyFingerprint(report.policyFingerprint());
        po.setSampleCount(report.sampleCount());
        po.setAuthorizationMismatchCount(report.authorizationMismatchCount());
        po.setCandidateRecallAt40(report.candidateRecallAt40());
        po.setBaselineRecallAt40(report.baselineRecallAt40());
        po.setCandidateNdcgAt16(report.candidateNdcgAt16());
        po.setBaselineNdcgAt16(report.baselineNdcgAt16());
        po.setCandidateP95LatencyMs(report.candidateP95LatencyMs());
        po.setBaselineP95LatencyMs(report.baselineP95LatencyMs());
        po.setEvaluatedAt(report.createdAt());
        return po;
    }

    private StoredArtifact artifact(String key) {
        return new StoredArtifact(key, "v1", "a".repeat(64), 10, "application/json+gzip");
    }

    private static IDocumentProcessingMapper unusedDocuments() {
        return proxy(IDocumentProcessingMapper.class, (method, args) -> unsupported(method));
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
