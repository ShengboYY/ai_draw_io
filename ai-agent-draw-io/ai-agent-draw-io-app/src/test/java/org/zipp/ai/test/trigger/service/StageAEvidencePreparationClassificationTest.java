package org.zipp.ai.test.trigger.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.multimodal.ObservationBounds;
import org.zipp.ai.domain.multimodal.ObservationKind;
import org.zipp.ai.domain.multimodal.VerifiedObservation;
import org.zipp.ai.domain.multimodal.VisualObservationModule;
import org.zipp.ai.domain.multimodal.VisualObservationOutcome;
import org.zipp.ai.domain.retrieval.CanvasProbe;
import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.domain.retrieval.EvidencePreparationCommand;
import org.zipp.ai.domain.retrieval.EvidenceProgressListener;
import org.zipp.ai.domain.retrieval.PreparationOutcome;
import org.zipp.ai.domain.retrieval.RunResourceDomain;
import org.zipp.ai.domain.retrieval.SourceMode;
import org.zipp.ai.domain.retrieval.ValidatedSelection;
import org.zipp.ai.domain.retrieval.internal.DefaultEvidencePreparationModule;
import org.zipp.ai.domain.retrieval.model.valobj.VectorIdPage;
import org.zipp.ai.domain.retrieval.model.valobj.VectorProjection;
import org.zipp.ai.domain.retrieval.port.AuthorizedCandidate;
import org.zipp.ai.domain.retrieval.port.AuthorizedSource;
import org.zipp.ai.domain.retrieval.port.AuthorizedSourceSet;
import org.zipp.ai.domain.retrieval.port.CandidateRef;
import org.zipp.ai.domain.retrieval.port.EvidenceBlobStore;
import org.zipp.ai.domain.retrieval.port.EvidenceCatalog;
import org.zipp.ai.domain.retrieval.port.EvidenceReadLeaseCoordinator;
import org.zipp.ai.domain.retrieval.port.EmbeddingPort;
import org.zipp.ai.domain.retrieval.port.MaterialRetrievalTelemetry;
import org.zipp.ai.domain.retrieval.port.RetrievalLexicalIndex;
import org.zipp.ai.domain.retrieval.port.RetrievalVectorIndex;
import org.zipp.ai.domain.retrieval.port.ServerCanvasPort;
import org.zipp.ai.domain.retrieval.port.SourceResolution;
import org.zipp.ai.domain.retrieval.port.TenantKeyPort;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the real preparation module using deterministic ports derived from the frozen cohort.
 * Ports stand in for source storage only; no remote vector store, OCR provider, or model is invoked.
 */
public class StageAEvidencePreparationClassificationTest {
    private static final CatalogOwner OWNER = new CatalogOwner(OwnerType.USER, "stage-a-owner");
    private static final StoredArtifact TEXT_ARTIFACT = new StoredArtifact("stage-a/text.txt", "stage-a-text-v1",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 512, "text/plain");
    private static final StoredArtifact VISUAL_ARTIFACT = new StoredArtifact("stage-a/visual.png", "stage-a-visual-v1",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 512, "image/png");

    @Test
    public void frozenCohortIsClassifiedThroughTheRealPreparationModule() throws Exception {
        JSONObject cohort = frozenCohort();
        JSONArray cases = cohort.getJSONArray("cases");
        JSONObject caseSetups = cohort.getJSONObject("setupCatalog").getJSONObject("caseSetups");
        int ready = 0;
        int insufficient = 0;
        int clarification = 0;
        int degraded = 0;
        int notRequired = 0;

        for (int index = 0; index < cases.size(); index++) {
            JSONObject cohortCase = cases.getJSONObject(index);
            Scenario scenario = new Scenario(cohortCase, caseSetups.getJSONObject(cohortCase.getString("caseId")));
            RunResourceDomain resources = new RunResourceDomain();
            PreparationOutcome outcome = scenario.module().prepare(scenario.command(), resources,
                    EvidenceProgressListener.NOOP, CancellationSignal.NEVER).toCompletableFuture().join();
            resources.closeExactlyOnce(org.zipp.ai.domain.retrieval.CloseReason.COMPLETED);

            assertEquals("case=" + scenario.caseId, scenario.expectedOutcome,
                    outcome.getClass().getSimpleName());
            switch (scenario.expectedOutcome) {
                case "Ready" -> {
                    ready++;
                    PreparationOutcome.Ready readyOutcome = (PreparationOutcome.Ready) outcome;
                    assertNotNull("case=" + scenario.caseId, readyOutcome.preparedEvidence().bundle());
                    assertTrue("case=" + scenario.caseId,
                            !readyOutcome.preparedEvidence().bundle().items().isEmpty());
                    assertTrue("case=" + scenario.caseId, readyOutcome.preparedEvidence().bundle().items().stream()
                            .anyMatch(item -> scenario.sourceVersion.equals(item.versionId())
                                    && scenario.requiredAnchorId.equals(item.evidenceId())));
                    if (scenario.requiresVisualArtifact) {
                        assertTrue("case=" + scenario.caseId, readyOutcome.preparedEvidence().bundle().items().stream()
                                .anyMatch(item -> "VISUAL".equals(item.modality())));
                    }
                }
                case "InsufficientEvidence" -> insufficient++;
                case "ClarificationNeeded" -> clarification++;
                case "DegradedDependency" -> degraded++;
                case "NotRequired" -> {
                    notRequired++;
                    assertEquals("case=" + scenario.caseId, 0, scenario.sourceResolutionCalls.get());
                }
                default -> throw new AssertionError("unexpected cohort outcome " + scenario.expectedOutcome);
            }
        }

        assertEquals(12, ready);
        assertEquals(6, insufficient);
        assertEquals(4, clarification);
        assertEquals(4, degraded);
        assertEquals(4, notRequired);
    }

    private JSONObject frozenCohort() throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && directory != null; depth++, directory = directory.getParent()) {
            Path fixture = directory.resolve("evaluation/material-rag-research-v1/fixtures/stage-a-evidence-decision-cohort-v1.json");
            if (Files.isRegularFile(fixture)) return JSON.parseObject(Files.readString(fixture));
        }
        throw new IllegalStateException("frozen Stage A cohort fixture was not found from the Maven working directory");
    }

    private static final class Scenario {
        private final String caseId;
        private final String request;
        private final String expectedOutcome;
        private final boolean requiresVisualArtifact;
        private final String sourceVersion;
        private final String requiredAnchorId;
        private final AtomicInteger sourceResolutionCalls = new AtomicInteger();

        private Scenario(JSONObject cohortCase, JSONObject setup) {
            this.caseId = cohortCase.getString("caseId");
            this.request = cohortCase.getString("request");
            this.expectedOutcome = cohortCase.getString("expectedOutcome");
            this.requiresVisualArtifact = cohortCase.getBooleanValue("requiresVisualArtifact");
            JSONArray sourceVersions = setup == null ? new JSONArray() : setup.getJSONArray("sourceVersions");
            JSONArray anchors = setup == null ? new JSONArray() : setup.getJSONArray("requiredAnchorIds");
            this.sourceVersion = sourceVersions.isEmpty() ? "" : sourceVersions.getString(0);
            this.requiredAnchorId = anchors.isEmpty() ? "" : anchors.getString(0);
        }

        private EvidencePreparationCommand command() {
            if ("NotRequired".equals(expectedOutcome)) {
                return new EvidencePreparationCommand(OWNER, "diagram-" + caseId, "conversation-" + caseId,
                        "request-" + caseId, "run-" + caseId, request, CanvasProbe.unavailableProbe(),
                        ValidatedSelection.empty(), SourceMode.NONE, List.of(), "NONE", "NONE");
            }
            String clarification = "ClarificationNeeded".equals(expectedOutcome)
                    ? (caseId.endsWith("03") ? "SOURCE" : "CLAIM") : "NONE";
            return new EvidencePreparationCommand(OWNER, "diagram-" + caseId, "conversation-" + caseId,
                    "request-" + caseId, "run-" + caseId, request, CanvasProbe.unavailableProbe(),
                    ValidatedSelection.empty(), SourceMode.EXPLICIT_ONLY, null, List.of(sourceVersion),
                    "REQUIRED", "NONE", clarification);
        }

        private DefaultEvidencePreparationModule module() {
            AuthorizedSource source = new AuthorizedSource("material-" + caseId, sourceVersion,
                    "revision-" + caseId, MaterialScopeType.LIBRARY, MaterialScopeType.PERSONAL_LIBRARY_KEY,
                    "READY", false, true, !requiresVisualArtifact, requiresVisualArtifact);
            AuthorizedCandidate candidate = new AuthorizedCandidate("chunk-" + caseId, requiredAnchorId,
                    source.materialId(), source.versionId(), source.revisionId(), requiresVisualArtifact ? "VISUAL" : "TEXT",
                    1, 0.95, requiresVisualArtifact ? VISUAL_ARTIFACT : TEXT_ARTIFACT, "Stage A " + caseId);
            EvidenceCatalog catalog = new EvidenceCatalog() {
                @Override
                public SourceResolution resolveSources(EvidencePreparationCommand command) {
                    sourceResolutionCalls.incrementAndGet();
                    if ("InsufficientEvidence".equals(expectedOutcome) && (caseId.endsWith("02") || caseId.endsWith("06"))) {
                        return new SourceResolution(SourceMode.EXPLICIT_ONLY, List.of(), List.of());
                    }
                    return new SourceResolution(SourceMode.EXPLICIT_ONLY, List.of(source), List.of());
                }

                @Override
                public List<CandidateRef> resolveVectorCandidates(List<String> vectorIds, AuthorizedSourceSet sources) {
                    return List.of();
                }

                @Override
                public List<AuthorizedCandidate> reauthorize(List<String> chunkIds, AuthorizedSourceSet sources, int limit) {
                    return List.of(candidate);
                }
            };

            RetrievalLexicalIndex lexical = (queries, sources, route, limit) -> {
                if ("DegradedDependency".equals(expectedOutcome) && (caseId.endsWith("01") || caseId.endsWith("04"))) {
                    throw new IllegalStateException("simulated retrieval dependency outage");
                }
                if ("InsufficientEvidence".equals(expectedOutcome)) return List.of();
                return List.of(new CandidateRef(candidate.chunkId(), candidate.modality(), 1.0));
            };
            EvidenceBlobStore blobs = (authorized, maximumBytes) -> {
                if ("sta-degraded-03".equals(caseId)) {
                    throw new IllegalStateException("simulated blob hydration failure");
                }
                return request + " documented source evidence for " + caseId;
            };
            Optional<VisualObservationModule> observations = requiresVisualArtifact || "sta-degraded-02".equals(caseId)
                    ? Optional.of(visualModule(candidate)) : Optional.empty();

            return new DefaultEvidencePreparationModule(catalog, lexical, denseEmbedding(), denseIndex(), tenantKeys(),
                    leases(), blobs, noCanvas(), ForkJoinPool.commonPool(), ForkJoinPool.commonPool(),
                    Duration.ofSeconds(2), Duration.ofSeconds(1),
                    MaterialRetrievalTelemetry.NOOP, observations);
        }

        private VisualObservationModule visualModule(AuthorizedCandidate candidate) {
            return (command, resources, cancellation) -> {
                if ("sta-degraded-02".equals(caseId)) {
                    return CompletableFuture.completedFuture(new VisualObservationOutcome.Unavailable("provider unavailable"));
                }
                return CompletableFuture.completedFuture(new VisualObservationOutcome.Verified(List.of(
                        new VerifiedObservation(candidate.evidenceId(), ObservationKind.ARROW, request,
                                new ObservationBounds(0.1, 0.1, 0.6, 0.2), "LEFT_TO_RIGHT", 0.98))));
            };
        }
    }

    private static Optional<EmbeddingPort> denseEmbedding() {
        return Optional.of((texts, inputType) -> List.of(new float[]{1.0f}));
    }

    private static Optional<RetrievalVectorIndex> denseIndex() {
        return Optional.of(new RetrievalVectorIndex() {
            @Override public void upsert(List<VectorProjection> projections) { throw new UnsupportedOperationException(); }
            @Override public Set<String> existingVectorIds(List<String> vectorIds) { throw new UnsupportedOperationException(); }
            @Override public VectorIdPage listVectorIds(String paginationToken, int limit) { throw new UnsupportedOperationException(); }
            @Override public List<String> query(float[] vector, String tenantKey, int topK) { return List.of(); }
            @Override public void delete(List<String> vectorIds) { throw new UnsupportedOperationException(); }
        });
    }

    private static TenantKeyPort tenantKeys() {
        return (ownerType, ownerKey) -> "stage-a-tenant";
    }

    private static EvidenceReadLeaseCoordinator leases() {
        return (owner, runId, sources) -> () -> { };
    }

    private static ServerCanvasPort noCanvas() {
        return (owner, diagramId) -> Optional.empty();
    }
}
