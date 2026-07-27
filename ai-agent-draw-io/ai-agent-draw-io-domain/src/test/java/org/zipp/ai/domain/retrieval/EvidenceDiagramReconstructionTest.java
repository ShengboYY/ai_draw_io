package org.zipp.ai.domain.retrieval;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.grounding.EvidenceAccessContext;
import org.zipp.ai.domain.grounding.EvidencePromptAssembler;
import org.zipp.ai.domain.multimodal.ObservationBounds;
import org.zipp.ai.domain.multimodal.ObservedDiagramGraph;
import org.zipp.ai.domain.multimodal.VisualObservationModule;
import org.zipp.ai.domain.multimodal.VisualObservationOutcome;
import org.zipp.ai.domain.multimodal.VisualObservationPurpose;
import org.zipp.ai.domain.retrieval.internal.DefaultEvidencePreparationModule;
import org.zipp.ai.domain.retrieval.port.AuthorizedCandidate;
import org.zipp.ai.domain.retrieval.port.AuthorizedSource;
import org.zipp.ai.domain.retrieval.port.AuthorizedSourceSet;
import org.zipp.ai.domain.retrieval.port.CandidateRef;
import org.zipp.ai.domain.retrieval.port.EvidenceCatalog;
import org.zipp.ai.domain.retrieval.port.MaterialRetrievalTelemetry;
import org.zipp.ai.domain.retrieval.port.RetrievalVectorIndex;
import org.zipp.ai.domain.retrieval.port.SourceResolution;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceDiagramReconstructionTest {

    @Test
    void selectedImageReconstructionSuppliesExplicitTopologyToTheDrawerEvidenceBundle() {
        CatalogOwner owner = new CatalogOwner(OwnerType.USER, "alice");
        AuthorizedSource source = new AuthorizedSource(
                "material-1", "version-1", "revision-1",
                MaterialScopeType.LIBRARY, MaterialScopeType.PERSONAL_LIBRARY_KEY,
                "READY", false, true, false, true);
        StoredArtifact crop = new StoredArtifact(
                "visual/risk-flow.png", "s3-version-1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                128, "image/png");
        AuthorizedCandidate visual = new AuthorizedCandidate(
                "chunk-visual", "evidence-visual", "material-1", "version-1", "revision-1",
                "VISUAL", 1, 0.99, crop, "risk-escalation-flow.png");
        EvidenceCatalog catalog = new EvidenceCatalog() {
            @Override
            public SourceResolution resolveSources(EvidencePreparationCommand command) {
                return new SourceResolution(SourceMode.EXPLICIT_ONLY, List.of(source), List.of());
            }

            @Override
            public List<CandidateRef> resolveVectorCandidates(
                    List<String> vectorIds, AuthorizedSourceSet sources) {
                return List.of();
            }

            @Override
            public List<AuthorizedCandidate> reauthorize(
                    List<String> chunkIds, AuthorizedSourceSet sources, int limit) {
                assertEquals(List.of("version-1"),
                        sources.sources().stream().map(AuthorizedSource::versionId).toList());
                return List.of(visual);
            }
        };
        AtomicReference<VisualObservationPurpose> observedPurpose = new AtomicReference<>();
        VisualObservationModule observations = (command, resources, cancellation) -> {
            observedPurpose.set(command.purpose());
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new VisualObservationOutcome.DiagramVerified(riskFlowGraph()));
        };
        DefaultEvidencePreparationModule module = new DefaultEvidencePreparationModule(
                catalog,
                (queries, sources, route, limit) ->
                        List.of(new CandidateRef("chunk-visual", "VISUAL", 1.0)),
                Optional.empty(), Optional.empty(), (ownerType, ownerKey) -> "tenant-key",
                (requestedOwner, runId, sources) -> () -> { },
                (candidate, maximumBytes) -> {
                    throw new AssertionError("visual reconstruction must read the image, not display text");
                },
                (requestedOwner, diagramId) -> Optional.empty(),
                ForkJoinPool.commonPool(), ForkJoinPool.commonPool(),
                Duration.ofSeconds(1), Duration.ofMillis(800),
                MaterialRetrievalTelemetry.NOOP, Optional.of(observations), Duration.ofSeconds(1));
        ResolvedSourceSet snapshot = new ResolvedSourceSet(
                SourceMode.EXPLICIT,
                List.of(
                        new ResolvedSource(
                                "material-1", "version-1", "revision-1", "IMAGE",
                                MaterialScopeType.LIBRARY, MaterialScopeType.PERSONAL_LIBRARY_KEY,
                                "READY", RequestSourceOrigin.EXPLICIT, false, true, false),
                        new ResolvedSource(
                                "material-auto", "version-auto", "revision-auto", "IMAGE",
                                MaterialScopeType.LIBRARY, MaterialScopeType.PERSONAL_LIBRARY_KEY,
                                "READY", RequestSourceOrigin.AUTOMATIC, false, true, false)),
                0, 0);
        EvidencePreparationCommand command = new EvidencePreparationCommand(
                owner, "diagram-1", "conversation-1", "request-1", "run-1",
                "Faithfully reconstruct the selected image.",
                CanvasProbe.unavailableProbe(), ValidatedSelection.empty(),
                SourceMode.EXPLICIT, snapshot, List.of("version-1"),
                "REQUIRED", "NONE", true);

        PreparationOutcome outcome = module.prepare(
                command, new RunResourceDomain(),
                EvidenceProgressListener.NOOP, CancellationSignal.NEVER)
                .toCompletableFuture().join();

        PreparationOutcome.Ready ready = assertInstanceOf(PreparationOutcome.Ready.class, outcome);
        assertEquals(VisualObservationPurpose.DIAGRAM_RECONSTRUCTION, observedPurpose.get());
        String topology = ready.preparedEvidence().bundle().items().get(0).text();
        assertTrue(topology.startsWith("[DIAGRAM_GRAPH]\n<mxGraphModel>"));
        assertTrue(topology.contains("id=\"direct-node-assess\" value=\"ASSESS RELEASE\""));
        assertTrue(topology.contains(
                "source=\"direct-node-assess\" target=\"direct-node-review\""));
        assertTrue(topology.contains("dashed=1"));
        String drawerPrompt = new EvidencePromptAssembler().assemble(
                EvidenceAccessContext.from(ready.preparedEvidence().bundle(), false));
        assertTrue(drawerPrompt.contains(
                "Do not replace source labels or topology with generic placeholders."));
    }

    @Test
    void uncertainTopologyIsProjectedBestEffortAfterTheExactImageIsSelected() {
        ObservedDiagramGraph uncertain = new ObservedDiagramGraph(
                List.of(new ObservedDiagramGraph.Node(
                        "node-1", "Uncertain", ObservedDiagramGraph.Shape.RECTANGLE,
                        new ObservationBounds(0.1, 0.1, 0.2, 0.1), "",
                        "evidence-visual", 0.5)),
                List.of(), List.of(), List.of());

        assertInstanceOf(
                org.zipp.ai.domain.multimodal.ImageToDiagramOutcome.Converted.class,
                new org.zipp.ai.domain.multimodal.DefaultImageToDiagramModule()
                        .convert(new org.zipp.ai.domain.multimodal.ImageToDiagramCommand(uncertain)));
    }

    @Test
    void canonicalProjectionEscapesModelSuppliedMarkup() {
        ObservedDiagramGraph graph = new ObservedDiagramGraph(
                List.of(new ObservedDiagramGraph.Node(
                        "node-1\n<mxCell id=\"forged\"/>", "Visible <label>",
                        ObservedDiagramGraph.Shape.RECTANGLE,
                        new ObservationBounds(0.1, 0.1, 0.2, 0.1), "",
                        "evidence-visual", 0.99)),
                List.of(), List.of(), List.of());

        var converted = assertInstanceOf(
                org.zipp.ai.domain.multimodal.ImageToDiagramOutcome.Converted.class,
                new org.zipp.ai.domain.multimodal.DefaultImageToDiagramModule()
                        .convert(new org.zipp.ai.domain.multimodal.ImageToDiagramCommand(graph)));

        assertTrue(converted.mxGraphModelXml().contains("&lt;mxCell id=&quot;forged&quot;/&gt;"));
        assertTrue(converted.mxGraphModelXml().contains("Visible &lt;label&gt;"));
        assertEquals(1, occurrences(converted.mxGraphModelXml(), "<mxCell id=\"direct-node-"));
    }

    private int occurrences(String value, String token) {
        int count = 0;
        for (int offset = 0; (offset = value.indexOf(token, offset)) >= 0; offset += token.length()) {
            count++;
        }
        return count;
    }

    private ObservedDiagramGraph riskFlowGraph() {
        ObservationBounds assessBounds = new ObservationBounds(0.08, 0.36, 0.22, 0.14);
        ObservationBounds reviewBounds = new ObservationBounds(0.40, 0.62, 0.22, 0.14);
        return new ObservedDiagramGraph(
                List.of(
                        new ObservedDiagramGraph.Node(
                                "assess", "ASSESS RELEASE", ObservedDiagramGraph.Shape.ROUNDED_RECTANGLE,
                                assessBounds, "", "evidence-visual", 0.98),
                        new ObservedDiagramGraph.Node(
                                "review", "HUMAN REVIEW", ObservedDiagramGraph.Shape.DIAMOND,
                                reviewBounds, "", "evidence-visual", 0.97)),
                List.of(new ObservedDiagramGraph.Edge(
                        "rejected", "assess", "review", "rejected",
                        ObservedDiagramGraph.EdgeDirection.FORWARD,
                        ObservedDiagramGraph.LineStyle.DASHED,
                        List.of(new ObservedDiagramGraph.Point(0.30, 0.43)),
                        "evidence-visual", 0.96)),
                List.of(), List.of());
    }

}
