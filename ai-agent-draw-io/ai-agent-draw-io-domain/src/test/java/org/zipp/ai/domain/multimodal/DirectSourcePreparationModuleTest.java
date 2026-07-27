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
        assertEquals(List.of("direct_visual_observation", "direct_diagram_projection",
                        "direct_visual_verification"),
                progressStages);
        resources.close();
        assertTrue(leaseClosed.get());
    }

    @Test
    void lowConfidenceRecognitionDoesNotInterruptDirectPreparation() {
        AtomicBoolean leaseClosed = new AtomicBoolean();
        DirectSourcePreparationModule module = module(
                (request, resources, cancellation) ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                new VisualObservationOutcome.DiagramVerified(graph(0.40))),
                readySources(), leaseClosed, Optional.of(artifact));
        RunResourceDomain resources = new RunResourceDomain();

        DirectSourceOutcome outcome = module.prepare(command(), resources,
                null, CancellationSignal.NEVER).toCompletableFuture().join();

        assertInstanceOf(DirectSourceOutcome.Prepared.class, outcome);
        resources.close();
        assertTrue(leaseClosed.get());
    }

    @Test
    void projectorReceivesBestEffortGraphAfterCanonicalTopologyValidation() {
        int[] conversions = {0};
        DirectSourcePreparationModule module = new DefaultDirectSourcePreparationModule(
                (request, resources, cancellation) ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                new VisualObservationOutcome.DiagramVerified(graph(0.40))),
                command -> {
                    conversions[0]++;
                    return new DefaultImageToDiagramModule().projectVerified(command.graph());
                },
                command -> readySources(),
                (owner, runId, authorizedSources) -> () -> { },
                pageAccess(Optional.of(artifact)));

        DirectSourceOutcome outcome = module.prepare(command(), new RunResourceDomain(),
                null, CancellationSignal.NEVER).toCompletableFuture().join();

        assertInstanceOf(DirectSourceOutcome.Prepared.class, outcome);
        assertEquals(1, conversions[0]);
    }

    @Test
    void retriesInvalidProviderOutputOnceAndThenPreparesTheDiagram() {
        int[] observations = {0};
        List<String> progressStages = new ArrayList<>();
        VisualObservationModule observer = (request, resources, cancellation) -> {
            observations[0]++;
            VisualObservationOutcome outcome = observations[0] == 1
                    ? new VisualObservationOutcome.Unavailable("VISUAL_PROVIDER_OUTPUT_INVALID")
                    : new VisualObservationOutcome.DiagramVerified(graph(0.94));
            return java.util.concurrent.CompletableFuture.completedFuture(outcome);
        };
        DirectSourcePreparationModule module = module(
                observer, readySources(), new AtomicBoolean(), Optional.of(artifact));

        DirectSourceOutcome outcome = module.prepare(command(), new RunResourceDomain(),
                (stage, completed, total) -> progressStages.add(stage),
                CancellationSignal.NEVER).toCompletableFuture().join();

        assertInstanceOf(DirectSourceOutcome.Prepared.class, outcome);
        assertEquals(2, observations[0]);
        assertEquals(1, progressStages.stream()
                .filter("direct_visual_retry"::equals).count());
    }

    @Test
    void retriesAnExceptionallyCompletedProviderCallOnce() {
        int[] observations = {0};
        DirectSourcePreparationModule module = module(
                (request, resources, cancellation) -> {
                    observations[0]++;
                    if (observations[0] == 1) {
                        return java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException("provider failed"));
                    }
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            new VisualObservationOutcome.DiagramVerified(graph(0.94)));
                },
                readySources(), new AtomicBoolean(), Optional.of(artifact));

        DirectSourceOutcome outcome = module.prepare(command(), new RunResourceDomain(),
                null, CancellationSignal.NEVER).toCompletableFuture().join();

        assertInstanceOf(DirectSourceOutcome.Prepared.class, outcome);
        assertEquals(2, observations[0]);
    }

    @Test
    void projectionFailureIsRetriedAndNeverReportedAsInvalidUserInput() {
        int[] observations = {0};
        VisualObservationModule observer = (request, resources, cancellation) -> {
            observations[0]++;
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new VisualObservationOutcome.DiagramVerified(graph(0.94)));
        };
        DirectSourcePreparationModule module = new DefaultDirectSourcePreparationModule(
                observer,
                command -> {
                    throw new IllegalArgumentException("projection failed");
                },
                command -> readySources(),
                (owner, runId, authorizedSources) -> () -> { },
                pageAccess(Optional.of(artifact)));

        DirectSourceOutcome outcome = module.prepare(command(), new RunResourceDomain(),
                null, CancellationSignal.NEVER).toCompletableFuture().join();

        assertEquals("DIRECT_PROJECTION_INVALID",
                assertInstanceOf(DirectSourceOutcome.Unavailable.class, outcome).reason());
        assertEquals(2, observations[0]);
    }

    @Test
    void recoverableTopologyFailureIsReobservedAndRedrawnOnce() {
        int[] observations = {0};
        DirectSourcePreparationModule module = module(
                (request, resources, cancellation) -> {
                    observations[0]++;
                    ObservedDiagramGraph observed = observations[0] == 1
                            ? graphWithUnknownEdgeTarget()
                            : graph(0.94);
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            new VisualObservationOutcome.DiagramVerified(observed));
                },
                readySources(), new AtomicBoolean(), Optional.of(artifact));

        DirectSourceOutcome outcome = module.prepare(command(), new RunResourceDomain(),
                null, CancellationSignal.NEVER).toCompletableFuture().join();

        assertInstanceOf(DirectSourceOutcome.Prepared.class, outcome);
        assertEquals(2, observations[0]);
    }

    @Test
    void failedProjectionVerificationIsRedrawnOnceBeforeCommitPreparation() {
        int[] conversions = {0};
        ImageToDiagramModule converter = command -> {
            conversions[0]++;
            if (conversions[0] == 1) {
                return new ImageToDiagramOutcome.Converted(
                        "<mxGraphModel><root><script/></root></mxGraphModel>",
                        List.of(), command.graph());
            }
            return new DefaultImageToDiagramModule().convert(command);
        };
        DirectSourcePreparationModule module = new DefaultDirectSourcePreparationModule(
                (request, resources, cancellation) ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                new VisualObservationOutcome.DiagramVerified(graph(0.94))),
                converter,
                command -> readySources(),
                (owner, runId, authorizedSources) -> () -> { },
                pageAccess(Optional.of(artifact)));

        DirectSourceOutcome outcome = module.prepare(command(), new RunResourceDomain(),
                null, CancellationSignal.NEVER).toCompletableFuture().join();

        assertInstanceOf(DirectSourceOutcome.Prepared.class, outcome);
        assertEquals(2, conversions[0]);
    }

    @Test
    void projectionCannotChangeTheObservedGraphAndItsXmlTogether() {
        int[] conversions = {0};
        ImageToDiagramModule converter = command -> {
            conversions[0]++;
            if (conversions[0] == 1) {
                ObservedDiagramGraph tampered = new ObservedDiagramGraph(
                        List.of(command.graph().nodes().get(0)),
                        List.of(), List.of(), List.of());
                return new DefaultImageToDiagramModule().convert(
                        new ImageToDiagramCommand(tampered));
            }
            return new DefaultImageToDiagramModule().convert(command);
        };
        DirectSourcePreparationModule module = new DefaultDirectSourcePreparationModule(
                (request, resources, cancellation) ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                new VisualObservationOutcome.DiagramVerified(graph(0.94))),
                converter,
                command -> readySources(),
                (owner, runId, authorizedSources) -> () -> { },
                pageAccess(Optional.of(artifact)));

        DirectSourceOutcome.Prepared prepared =
                assertInstanceOf(DirectSourceOutcome.Prepared.class,
                        module.prepare(command(), new RunResourceDomain(), null,
                                CancellationSignal.NEVER).toCompletableFuture().join());

        assertEquals(2, conversions[0]);
        assertEquals(List.of("a", "b"),
                prepared.graph().nodes().stream().map(ObservedDiagramGraph.Node::id).toList());
    }

    @Test
    void repairsMaterialNodeOverlapBeforeProjection() {
        List<String> progressStages = new ArrayList<>();
        ObservedDiagramGraph overlapping = new ObservedDiagramGraph(
                List.of(
                        new ObservedDiagramGraph.Node("a", "A",
                                ObservedDiagramGraph.Shape.RECTANGLE,
                                new ObservationBounds(0.1, 0.2, 0.2, 0.1),
                                "", EVIDENCE_ID, 0.96),
                        new ObservedDiagramGraph.Node("b", "B",
                                ObservedDiagramGraph.Shape.ELLIPSE,
                                new ObservationBounds(0.1, 0.2, 0.2, 0.1),
                                "", EVIDENCE_ID, 0.97)),
                List.of(), List.of(), List.of());
        DirectSourcePreparationModule module = module(
                (request, resources, cancellation) ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                new VisualObservationOutcome.DiagramVerified(overlapping)),
                readySources(), new AtomicBoolean(), Optional.of(artifact));

        DirectSourceOutcome.Prepared prepared =
                assertInstanceOf(DirectSourceOutcome.Prepared.class,
                        module.prepare(command(), new RunResourceDomain(),
                                (stage, completed, total) -> progressStages.add(stage),
                                CancellationSignal.NEVER).toCompletableFuture().join());

        assertTrue(progressStages.contains("direct_geometry_repair"));
        assertEquals(new ObservationBounds(0.1, 0.2, 0.2, 0.1),
                prepared.graph().nodes().get(0).bounds());
        assertTrue(!prepared.graph().nodes().get(0).bounds()
                .equals(prepared.graph().nodes().get(1).bounds()));
    }

    @Test
    void repairsGroupedNodeBackInsideItsContainer() {
        ObservationBounds groupBounds = new ObservationBounds(0.2, 0.2, 0.5, 0.5);
        ObservedDiagramGraph outsideGroup = new ObservedDiagramGraph(
                List.of(new ObservedDiagramGraph.Node("a", "A",
                        ObservedDiagramGraph.Shape.RECTANGLE,
                        new ObservationBounds(0.05, 0.05, 0.1, 0.1),
                        "g", EVIDENCE_ID, 0.96)),
                List.of(),
                List.of(new ObservedDiagramGraph.Group(
                        "g", "Group", ObservedDiagramGraph.GroupKind.CONTAINER,
                        groupBounds, EVIDENCE_ID, 0.97)),
                List.of());
        DirectSourcePreparationModule module = module(
                (request, resources, cancellation) ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                new VisualObservationOutcome.DiagramVerified(outsideGroup)),
                readySources(), new AtomicBoolean(), Optional.of(artifact));

        DirectSourceOutcome.Prepared prepared =
                assertInstanceOf(DirectSourceOutcome.Prepared.class,
                        module.prepare(command(), new RunResourceDomain(), null,
                                CancellationSignal.NEVER).toCompletableFuture().join());

        ObservationBounds repaired = prepared.graph().nodes().get(0).bounds();
        assertTrue(repaired.x() >= groupBounds.x());
        assertTrue(repaired.y() >= groupBounds.y());
        assertTrue(repaired.x() + repaired.width() <= groupBounds.x() + groupBounds.width());
        assertTrue(repaired.y() + repaired.height() <= groupBounds.y() + groupBounds.height());
    }

    @Test
    void repairsSubpixelGeometryToAVisibleCanvasCell() {
        ObservedDiagramGraph subpixel = new ObservedDiagramGraph(
                List.of(new ObservedDiagramGraph.Node("a", "A",
                        ObservedDiagramGraph.Shape.RECTANGLE,
                        new ObservationBounds(0.1, 0.2, 0.00001, 0.00001),
                        "", EVIDENCE_ID, 0.96)),
                List.of(), List.of(), List.of());
        DirectSourcePreparationModule module = module(
                (request, resources, cancellation) ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                new VisualObservationOutcome.DiagramVerified(subpixel)),
                readySources(), new AtomicBoolean(), Optional.of(artifact));

        DirectSourceOutcome.Prepared prepared =
                assertInstanceOf(DirectSourceOutcome.Prepared.class,
                        module.prepare(command(), new RunResourceDomain(), null,
                                CancellationSignal.NEVER).toCompletableFuture().join());

        assertTrue(prepared.graph().nodes().get(0).bounds().width() >= 1.0 / 1_200);
        assertTrue(prepared.graph().nodes().get(0).bounds().height() >= 1.0 / 800);
        assertTrue(prepared.mxGraphModelXml().contains("width=\"1\" height=\"1\""));
    }

    @Test
    void separatesOverlappingGroupsAndTheirNodes() {
        ObservationBounds groupBounds = new ObservationBounds(0.1, 0.1, 0.3, 0.3);
        ObservedDiagramGraph overlappingGroups = new ObservedDiagramGraph(
                List.of(
                        new ObservedDiagramGraph.Node("a", "A",
                                ObservedDiagramGraph.Shape.RECTANGLE,
                                new ObservationBounds(0.15, 0.15, 0.1, 0.1),
                                "g1", EVIDENCE_ID, 0.96),
                        new ObservedDiagramGraph.Node("b", "B",
                                ObservedDiagramGraph.Shape.RECTANGLE,
                                new ObservationBounds(0.15, 0.15, 0.1, 0.1),
                                "g2", EVIDENCE_ID, 0.96)),
                List.of(new ObservedDiagramGraph.Edge(
                        "a-to-b", "a", "b", "",
                        ObservedDiagramGraph.EdgeDirection.FORWARD,
                        ObservedDiagramGraph.LineStyle.SOLID,
                        List.of(new ObservedDiagramGraph.Point(0.2, 0.2)),
                        EVIDENCE_ID, 0.96)),
                List.of(
                        new ObservedDiagramGraph.Group("g1", "One",
                                ObservedDiagramGraph.GroupKind.CONTAINER,
                                groupBounds, EVIDENCE_ID, 0.97),
                        new ObservedDiagramGraph.Group("g2", "Two",
                                ObservedDiagramGraph.GroupKind.CONTAINER,
                                groupBounds, EVIDENCE_ID, 0.97)),
                List.of());
        DirectSourcePreparationModule module = module(
                (request, resources, cancellation) ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                new VisualObservationOutcome.DiagramVerified(overlappingGroups)),
                readySources(), new AtomicBoolean(), Optional.of(artifact));

        DirectSourceOutcome.Prepared prepared =
                assertInstanceOf(DirectSourceOutcome.Prepared.class,
                        module.prepare(command(), new RunResourceDomain(), null,
                                CancellationSignal.NEVER).toCompletableFuture().join());

        assertTrue(!prepared.graph().groups().get(0).bounds()
                .equals(prepared.graph().groups().get(1).bounds()));
        assertTrue(!prepared.graph().nodes().get(0).bounds()
                .equals(prepared.graph().nodes().get(1).bounds()));
        assertTrue(prepared.graph().edges().get(0).waypoints().isEmpty());
    }

    @Test
    void actualInvalidVisualInputIsRejectedWithoutRetry() {
        int[] observations = {0};
        DirectSourcePreparationModule module = module(
                (request, resources, cancellation) -> {
                    observations[0]++;
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            new VisualObservationOutcome.Rejected("INVALID_VISUAL_INPUT"));
                },
                readySources(), new AtomicBoolean(), Optional.of(artifact));

        DirectSourceOutcome outcome = module.prepare(command(), new RunResourceDomain(),
                null, CancellationSignal.NEVER).toCompletableFuture().join();

        assertEquals(List.of("INVALID_VISUAL_INPUT"),
                assertInstanceOf(DirectSourceOutcome.Rejected.class, outcome).reasons());
        assertEquals(1, observations[0]);
    }

    @Test
    void retriesAnIncompleteDiagramObservationWithoutRequestingUserConfirmation() {
        String gapText = "arrow direction unclear";
        int[] observations = {0};
        VisualObservationModule observer = (request, resources, cancellation) -> {
            observations[0]++;
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new VisualObservationOutcome.Gap(List.of(gapText)));
        };
        DirectSourcePreparationModule module = module(
                observer, readySources(), new AtomicBoolean(), Optional.of(artifact));

        DirectSourceOutcome.Unavailable unavailable =
                assertInstanceOf(DirectSourceOutcome.Unavailable.class,
                        module.prepare(command(), new RunResourceDomain(), null,
                                CancellationSignal.NEVER).toCompletableFuture().join());

        assertEquals("VISUAL_DIAGRAM_OBSERVATION_INCOMPLETE", unavailable.reason());
        assertEquals(2, observations[0]);
    }

    @Test
    void passesBoundedUserClarificationsToTheRepeatObservation() {
        VisualObservationModule observations = (request, resources, cancellation) -> {
            assertTrue(request.question().contains(
                    "UNRESOLVED_EDGE_DIRECTION:e1=FORWARD"));
            assertTrue(request.question().contains(
                    "LOW_CONFIDENCE_NODE_TEXT:n2=ACCEPT_OBSERVED"));
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new VisualObservationOutcome.DiagramVerified(graph(0.94)));
        };
        DirectSourcePreparationModule module = module(
                observations, readySources(), new AtomicBoolean(), Optional.of(artifact));
        DirectSourceCommand confirmed = new DirectSourceCommand(
                new CatalogOwner(OwnerType.USER, "alice"),
                "request-1", "run-1", "diagram-1", "conversation-1", "upload-1",
                List.of("selected-version-1"), SourceMode.EXPLICIT_ONLY,
                "Reconstruct the uploaded diagram",
                "version-1",
                List.of(
                        new DirectClarification("UNRESOLVED_EDGE_DIRECTION:e1",
                                DirectClarification.Resolution.FORWARD,
                                DirectObservationFingerprint.of("a → b")),
                        new DirectClarification("LOW_CONFIDENCE_NODE_TEXT:n2",
                                DirectClarification.Resolution.ACCEPT_OBSERVED,
                                DirectObservationFingerprint.of("Approve order"))),
                null);

        DirectSourceOutcome outcome = module.prepare(confirmed, new RunResourceDomain(),
                null, CancellationSignal.NEVER).toCompletableFuture().join();

        assertInstanceOf(DirectSourceOutcome.Prepared.class, outcome);
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
    void preparesAnAuthorizedChartbookImageWithoutAttachmentOrVectorLookup() {
        AtomicBoolean leaseClosed = new AtomicBoolean();
        ResolvedSource chartbookImage = new ResolvedSource(
                "material-1", "version-1", "revision-1", "IMAGE",
                "incident.png", MaterialScopeType.CHARTBOOK, "chartbook-1", "READY",
                RequestSourceOrigin.AUTOMATIC, false, true, false);
        ResolvedSourceSet snapshot = new ResolvedSourceSet(
                SourceMode.AUTO, List.of(chartbookImage), 0, 0);
        DirectSourcePreparationModule module = module(
                (request, resources, cancellation) ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                new VisualObservationOutcome.DiagramVerified(graph(0.94))),
                snapshot, leaseClosed, Optional.of(artifact));
        DirectSourceCommand command = new DirectSourceCommand(
                new CatalogOwner(OwnerType.USER, "alice"),
                "request-1", "run-1", "diagram-1", "conversation-1", "",
                List.of(), SourceMode.AUTO, "Reconstruct incident.png", "",
                List.of(), snapshot, "version-1");

        DirectSourceOutcome outcome = module.prepare(command, new RunResourceDomain(),
                null, CancellationSignal.NEVER).toCompletableFuture().join();

        assertInstanceOf(DirectSourceOutcome.Prepared.class, outcome);
    }

    @Test
    void staleImageConfirmationStopsBeforeReadingPixels() {
        int[] observations = {0};
        DirectSourcePreparationModule module = module(
                (request, resources, cancellation) -> {
                    observations[0]++;
                    throw new AssertionError("stale confirmation must not read pixels");
                },
                readySources(), new AtomicBoolean(), Optional.of(artifact));
        DirectSourceCommand stale = new DirectSourceCommand(
                new CatalogOwner(OwnerType.USER, "alice"),
                "request-1", "run-1", "diagram-1", "conversation-1", "upload-1",
                List.of("selected-version-1"), SourceMode.EXPLICIT_ONLY, "Reconstruct",
                "another-version",
                List.of(new DirectClarification("LOW_CONFIDENCE_NODE_TEXT:n2",
                        DirectClarification.Resolution.ACCEPT_OBSERVED,
                        DirectObservationFingerprint.of("Approve order"))),
                null);

        DirectSourceOutcome outcome = module.prepare(stale, new RunResourceDomain(),
                null, CancellationSignal.NEVER).toCompletableFuture().join();

        assertEquals(List.of("DIRECT_CONFIRMATION_SOURCE_MISMATCH"),
                assertInstanceOf(DirectSourceOutcome.Rejected.class, outcome).reasons());
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
    void readableVisualArtifactCanBePreparedIndependentOfRetrievalProcessingState() {
        for (String state : List.of("PARTIAL_READY", "PROCESSING", "FAILED")) {
            DirectSourcePreparationModule module = module(
                    (request, resources, cancellation) ->
                            java.util.concurrent.CompletableFuture.completedFuture(
                                    new VisualObservationOutcome.DiagramVerified(graph(0.94))),
                    sources("IMAGE", state), new AtomicBoolean(), Optional.of(artifact));

            DirectSourceOutcome outcome = module.prepare(command(), new RunResourceDomain(),
                    null, CancellationSignal.NEVER).toCompletableFuture().join();

            assertInstanceOf(DirectSourceOutcome.Prepared.class, outcome);
        }
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

    private ObservedDiagramGraph graphWithUnknownEdgeTarget() {
        ObservedDiagramGraph valid = graph(0.94);
        return new ObservedDiagramGraph(
                valid.nodes(),
                List.of(new ObservedDiagramGraph.Edge("a-to-missing", "a", "missing", "",
                        ObservedDiagramGraph.EdgeDirection.FORWARD,
                        ObservedDiagramGraph.LineStyle.SOLID, List.of(),
                        EVIDENCE_ID, 0.94)),
                valid.groups(), valid.unresolvedItems());
    }
}
