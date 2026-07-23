package org.zipp.ai.domain.multimodal;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.port.MaterialPageAccessPort;
import org.zipp.ai.domain.citation.service.CitationGuard;
import org.zipp.ai.domain.retrieval.*;
import org.zipp.ai.domain.retrieval.port.EvidenceReadLeaseCoordinator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectSourcePreparationModuleTest {
    private static final String EVIDENCE_ID = "direct-image-revision-1-p1";
    private final StoredArtifact artifact = new StoredArtifact("visual/source.png", "object-version-1",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            4, "image/png");

    @Test
    void preparesEditableXmlUsingOnlyTheExactImageAndStructuredModel() {
        List<String> progressStages = new ArrayList<>();
        AtomicBoolean leaseClosed = new AtomicBoolean();
        VisualObservationModule observations = (request, resources, cancellation) -> {
            assertEquals(VisualObservationPurpose.DIAGRAM_RECONSTRUCTION, request.purpose());
            assertEquals(EVIDENCE_ID, request.targets().get(0).evidenceId());
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new VisualObservationOutcome.DiagramVerified(graph(0.94)));
        };
        DirectSourcePreparationModule module = module(observations, readySources(),
                leaseClosed, Optional.of(artifact));
        RunResourceDomain resources = new RunResourceDomain();

        DirectSourceOutcome outcome = module.prepare(command(), resources,
                (stage, completed, total) -> progressStages.add(stage),
                CancellationSignal.NEVER).toCompletableFuture().join();

        DirectSourceOutcome.Prepared prepared =
                assertInstanceOf(DirectSourceOutcome.Prepared.class, outcome);
        assertTrue(prepared.mxGraphModelXml().contains("direct-edge-a-to-b"));
        assertEquals(3, prepared.citationBindings().size());
        assertEquals(List.of("direct-node-a", "direct-node-b", "direct-edge-a-to-b"),
                prepared.citationBindings().stream().map(binding -> binding.cellId()).toList());
        assertEquals(3, prepared.evidenceAccess().items().size());
        assertTrue(prepared.evidenceAccess().items().stream()
                .allMatch(item -> item.origin() == EvidenceOrigin.DIRECT_ATTACHMENT));
        var citationValidation = new CitationGuard(requests -> List.of()).validate(
                prepared.mxGraphModelXml(), prepared.citationBindings(),
                prepared.evidenceAccess(), true);
        assertTrue(citationValidation.accepted(), citationValidation.errors().toString());
        assertEquals(List.of("direct_visual_observation", "direct_diagram_projection"),
                progressStages);
        resources.close();
        assertTrue(leaseClosed.get());
    }

    @Test
    void preservesConfirmationBoundaryFromConversion() {
        AtomicBoolean leaseClosed = new AtomicBoolean();
        DirectSourcePreparationModule module = module(
                (request, resources, cancellation) ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                new VisualObservationOutcome.DiagramVerified(graph(0.40))),
                readySources(), leaseClosed, Optional.of(artifact));
        RunResourceDomain resources = new RunResourceDomain();

        DirectSourceOutcome outcome = module.prepare(command(), resources,
                null, CancellationSignal.NEVER).toCompletableFuture().join();

        DirectSourceOutcome.NeedsConfirmation confirmation =
                assertInstanceOf(DirectSourceOutcome.NeedsConfirmation.class, outcome);
        assertEquals(List.of("LOW_CONFIDENCE_EDGE:a-to-b"), confirmation.reasons());
        resources.close();
        assertTrue(leaseClosed.get());
    }

    @Test
    void cancellationStopsBeforeReadingPixels() {
        int[] observations = {0};
        DirectSourcePreparationModule module = module(
                (request, resources, cancellation) -> {
                    observations[0]++;
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            new VisualObservationOutcome.DiagramVerified(graph(0.94)));
                },
                readySources(), new AtomicBoolean(), Optional.of(artifact));

        DirectSourceOutcome outcome = module.prepare(command(), new RunResourceDomain(),
                null, () -> true).toCompletableFuture().join();

        assertInstanceOf(DirectSourceOutcome.Cancelled.class, outcome);
        assertEquals(0, observations[0]);
    }

    @Test
    void unauthorizedAttachmentStopsBeforeLeaseAndPixelRead() {
        ResolvedSourceSet unavailable = new ResolvedSourceSet(
                SourceMode.EXPLICIT_ONLY, List.of(), 0, 1);
        assertRejectedBeforeLeaseAndPixelRead(unavailable);
    }

    @Test
    void pdfAttachmentIsRejectedInsteadOfSilentlyConvertingOnlyPageOne() {
        assertRejectedBeforeLeaseAndPixelRead(sources("PDF", "READY"));
    }

    @Test
    void partiallyReadyImageIsRejectedBeforeLeaseAcquisition() {
        assertRejectedBeforeLeaseAndPixelRead(sources("IMAGE", "PARTIAL_READY"));
    }

    private void assertRejectedBeforeLeaseAndPixelRead(ResolvedSourceSet sources) {
        int[] observations = {0};
        int[] leases = {0};
        DirectSourcePreparationModule module = new DefaultDirectSourcePreparationModule(
                (request, resources, cancellation) -> {
                    observations[0]++;
                    throw new AssertionError("pixels must not be read");
                },
                new DefaultImageToDiagramModule(),
                command -> sources,
                (owner, runId, authorizedSources) -> {
                    leases[0]++;
                    return () -> { };
                },
                pageAccess(Optional.of(artifact)));

        DirectSourceOutcome outcome = module.prepare(command(), new RunResourceDomain(),
                null, CancellationSignal.NEVER).toCompletableFuture().join();

        assertEquals(List.of("DIRECT_ATTACHMENT_NOT_AUTHORIZED_OR_READY"),
                assertInstanceOf(DirectSourceOutcome.Rejected.class, outcome).reasons());
        assertEquals(0, leases[0]);
        assertEquals(0, observations[0]);
    }

    private DirectSourceCommand command() {
        return new DirectSourceCommand(new CatalogOwner(OwnerType.USER, "alice"),
                "request-1", "run-1", "diagram-1", "conversation-1", "upload-1",
                java.util.Arrays.asList(null, " selected-version-1 ", "", "selected-version-1"),
                SourceMode.EXPLICIT_ONLY,
                "Reconstruct the uploaded diagram");
    }

    private DirectSourcePreparationModule module(VisualObservationModule observations,
                                                 ResolvedSourceSet sources,
                                                 AtomicBoolean leaseClosed,
                                                 Optional<StoredArtifact> storedArtifact) {
        EvidenceReadLeaseCoordinator leases = (owner, runId, authorized) -> {
            assertEquals("alice", owner.ownerKey());
            assertEquals("run-1", runId);
            assertEquals(List.of("version-1"), authorized.sources().stream()
                    .map(source -> source.versionId()).toList());
            return () -> leaseClosed.set(true);
        };
        return new DefaultDirectSourcePreparationModule(observations,
                new DefaultImageToDiagramModule(), command -> {
                    assertEquals(List.of("upload-1"), command.attachmentUploadIds());
                    assertEquals(List.of("selected-version-1"), command.selectedVersionIds());
                    assertEquals("conversation-1", command.conversationId());
                    return sources;
                }, leases, pageAccess(storedArtifact));
    }

    private ResolvedSourceSet readySources() {
        return sources("IMAGE", "READY");
    }

    private ResolvedSourceSet sources(String kind, String state) {
        return new ResolvedSourceSet(SourceMode.EXPLICIT_ONLY, List.of(new ResolvedSource(
                "material-1", "version-1", "revision-1", kind,
                MaterialScopeType.CONVERSATION, "conversation-1", state,
                RequestSourceOrigin.ATTACHMENT, false, true, false)), 0, 0);
    }

    private MaterialPageAccessPort pageAccess(Optional<StoredArtifact> storedArtifact) {
        return new MaterialPageAccessPort() {
            @Override
            public Optional<org.zipp.ai.domain.material.model.valobj.MaterialPageSet> findPages(
                    CatalogOwner owner, String materialId, String versionId, String revisionId) {
                throw new AssertionError("page listing is not needed");
            }

            @Override
            public Optional<StoredArtifact> findPreviewArtifact(CatalogOwner owner, String materialId,
                                                                String versionId, String revisionId,
                                                                int pageNo) {
                assertEquals(1, pageNo);
                return storedArtifact;
            }

            @Override
            public Optional<org.zipp.ai.domain.material.model.valobj.MaterialReprocessSnapshot>
            findReprocessSnapshot(CatalogOwner owner, String materialId) {
                throw new AssertionError("reprocessing is not needed");
            }

            @Override
            public org.zipp.ai.domain.material.model.valobj.MaterialReprocessResult createOrFindRevision(
                    org.zipp.ai.domain.material.model.valobj.MaterialReprocessPlan plan) {
                throw new AssertionError("reprocessing is not needed");
            }
        };
    }

    private ObservedDiagramGraph graph(double edgeConfidence) {
        return new ObservedDiagramGraph(
                List.of(
                        new ObservedDiagramGraph.Node("a", "A", ObservedDiagramGraph.Shape.RECTANGLE,
                                new ObservationBounds(0.1, 0.2, 0.2, 0.1),
                                "", EVIDENCE_ID, 0.96),
                        new ObservedDiagramGraph.Node("b", "B", ObservedDiagramGraph.Shape.ELLIPSE,
                                new ObservationBounds(0.6, 0.2, 0.2, 0.1),
                                "", EVIDENCE_ID, 0.97)),
                List.of(new ObservedDiagramGraph.Edge("a-to-b", "a", "b", "",
                        ObservedDiagramGraph.EdgeDirection.FORWARD,
                        ObservedDiagramGraph.LineStyle.SOLID, List.of(),
                        EVIDENCE_ID, edgeConfidence)),
                List.of(), List.of());
    }
}
