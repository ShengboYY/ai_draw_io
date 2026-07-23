package org.zipp.ai.domain.multimodal;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.domain.retrieval.CloseReason;
import org.zipp.ai.domain.retrieval.RunResourceDomain;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualObservationModuleTest {
    private final CatalogOwner owner = new CatalogOwner(OwnerType.USER, "alice");
    private final VisualObservationTarget target = new VisualObservationTarget(
            "evidence-1", "material-1", "version-1", "revision-1", 4,
            "Guide", new StoredArtifact("visual/crop.png", "s3-version-1",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            4, "image/png"));

    @Test
    void returnsOnlyBoundedVerifiedStructuredObservations() {
        VisualObservationModule module = new DefaultVisualObservationModule(
                (artifact, maximumBytes) -> new byte[]{1, 2, 3, 4},
                request -> new VisionModelPort.Response(List.of(
                        new VerifiedObservation("evidence-1", ObservationKind.ARROW,
                                "Approval flows from Review to Done",
                                new ObservationBounds(0.1, 0.2, 0.8, 0.3),
                                "LEFT_TO_RIGHT", 0.93)), List.of()));

        VisualObservationOutcome outcome = module.observe(command(), new RunResourceDomain(),
                CancellationSignal.NEVER).toCompletableFuture().join();

        VisualObservationOutcome.Verified verified =
                assertInstanceOf(VisualObservationOutcome.Verified.class, outcome);
        assertEquals("Approval flows from Review to Done", verified.observations().get(0).text());
        assertEquals("evidence-1", verified.observations().get(0).evidenceId());
    }

    @Test
    void lowConfidenceVisualFactsReturnAGapInsteadOfEvidence() {
        VisualObservationModule module = new DefaultVisualObservationModule(
                (artifact, maximumBytes) -> new byte[]{1, 2, 3, 4},
                request -> new VisionModelPort.Response(List.of(
                        new VerifiedObservation("evidence-1", ObservationKind.ARROW,
                                "Possible arrow", new ObservationBounds(0, 0, 1, 1),
                                "UNKNOWN", 0.42)), List.of()));

        VisualObservationOutcome outcome = module.observe(command(), new RunResourceDomain(),
                CancellationSignal.NEVER).toCompletableFuture().join();

        assertInstanceOf(VisualObservationOutcome.Gap.class, outcome);
    }

    @Test
    void diagramReconstructionReturnsVerifiedGraphThroughTheSharedBoundary() {
        ObservedDiagramGraph graph = new ObservedDiagramGraph(
                List.of(new ObservedDiagramGraph.Node("review", "Review",
                        ObservedDiagramGraph.Shape.RECTANGLE,
                        new ObservationBounds(0.1, 0.2, 0.2, 0.1),
                        "", "evidence-1", 0.95)),
                List.of(), List.of(), List.of());
        VisualObservationModule module = new DefaultVisualObservationModule(
                (artifact, maximumBytes) -> new byte[]{1, 2, 3, 4},
                request -> new VisionModelPort.Response(List.of(), graph, List.of()));
        VisualObservationCommand command = new VisualObservationCommand(owner, "request-1", "run-1",
                VisualObservationPurpose.DIAGRAM_RECONSTRUCTION, "Reconstruct this diagram",
                List.of(target), 32);

        VisualObservationOutcome outcome = module.observe(command, new RunResourceDomain(),
                CancellationSignal.NEVER).toCompletableFuture().join();

        assertEquals(graph, assertInstanceOf(
                VisualObservationOutcome.DiagramVerified.class, outcome).graph());
    }

    @Test
    void cancellationStopsBeforeReadingPixelsOrCallingTheModel() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        VisualObservationModule module = new DefaultVisualObservationModule(
                (artifact, maximumBytes) -> {
                    reads.incrementAndGet();
                    return new byte[]{1};
                },
                request -> {
                    calls.incrementAndGet();
                    return new VisionModelPort.Response(List.of(), List.of());
                });

        VisualObservationOutcome outcome = module.observe(command(), new RunResourceDomain(),
                () -> true).toCompletableFuture().join();

        assertInstanceOf(VisualObservationOutcome.Cancelled.class, outcome);
        assertEquals(0, reads.get());
        assertEquals(0, calls.get());
    }

    @Test
    void closedRunStopsBeforeReadingPixelsOrCallingTheModel() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        VisualObservationModule module = new DefaultVisualObservationModule(
                (artifact, maximumBytes) -> {
                    reads.incrementAndGet();
                    return new byte[]{1};
                },
                request -> {
                    calls.incrementAndGet();
                    return new VisionModelPort.Response(List.of(), List.of());
                });
        RunResourceDomain resources = new RunResourceDomain();
        resources.closeExactlyOnce(CloseReason.CANCELLED);

        VisualObservationOutcome outcome = module.observe(command(), resources,
                CancellationSignal.NEVER).toCompletableFuture().join();

        assertInstanceOf(VisualObservationOutcome.Cancelled.class, outcome);
        assertEquals(0, reads.get());
        assertEquals(0, calls.get());
    }

    @Test
    void timeoutInterruptsAProviderCall() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean sawInterrupt = new AtomicBoolean();
        try {
            VisualObservationModule module = new DefaultVisualObservationModule(
                    (artifact, maximumBytes) -> new byte[]{1, 2, 3, 4},
                    request -> {
                        started.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException exception) {
                            sawInterrupt.set(true);
                            interrupted.countDown();
                            Thread.currentThread().interrupt();
                        }
                        return new VisionModelPort.Response(List.of(), List.of());
                    }, executor, 50);

            VisualObservationOutcome outcome = module.observe(command(), new RunResourceDomain(),
                    CancellationSignal.NEVER).toCompletableFuture().join();

            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertInstanceOf(VisualObservationOutcome.Unavailable.class, outcome);
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertTrue(sawInterrupt.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void closingRunInterruptsAnInFlightProviderCall() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            VisualObservationModule module = new DefaultVisualObservationModule(
                    (artifact, maximumBytes) -> new byte[]{1, 2, 3, 4},
                    request -> {
                        started.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException exception) {
                            interrupted.countDown();
                            Thread.currentThread().interrupt();
                        }
                        return new VisionModelPort.Response(List.of(), List.of());
                    }, executor, 5_000);
            RunResourceDomain resources = new RunResourceDomain();

            var outcome = module.observe(command(), resources, CancellationSignal.NEVER);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            resources.closeExactlyOnce(CloseReason.CANCELLED);

            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertInstanceOf(VisualObservationOutcome.Cancelled.class,
                    outcome.toCompletableFuture().get(1, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private VisualObservationCommand command() {
        return new VisualObservationCommand(owner, "request-1", "run-1",
                VisualObservationPurpose.FACT_VERIFICATION,
                "Which direction does the approval arrow point?", List.of(target), 8);
    }
}
