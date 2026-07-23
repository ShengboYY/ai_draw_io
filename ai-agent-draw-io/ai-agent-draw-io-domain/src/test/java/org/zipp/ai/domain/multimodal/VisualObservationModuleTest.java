package org.zipp.ai.domain.multimodal;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.domain.retrieval.RunResourceDomain;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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

    private VisualObservationCommand command() {
        return new VisualObservationCommand(owner, "request-1", "run-1",
                VisualObservationPurpose.FACT_VERIFICATION,
                "Which direction does the approval arrow point?", List.of(target), 8);
    }
}
