package org.zipp.ai.domain.multimodal;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.citation.service.CitationGuard;
import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.domain.retrieval.RunResourceDomain;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectSourcePreparationModuleTest {
    private final VisualObservationTarget target = new VisualObservationTarget(
            "evidence-image", "material-1", "version-1", "revision-1", 1,
            "uploaded diagram", new StoredArtifact("visual/source.png", "object-version-1",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            4, "image/png"));

    @Test
    void preparesEditableXmlUsingOnlyTheExactImageAndStructuredModel() {
        List<String> progressStages = new ArrayList<>();
        VisualObservationModule observations = (request, resources, cancellation) -> {
            assertEquals(VisualObservationPurpose.DIAGRAM_RECONSTRUCTION, request.purpose());
            assertEquals("evidence-image", request.targets().get(0).evidenceId());
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new VisualObservationOutcome.DiagramVerified(graph(0.94)));
        };
        DirectSourcePreparationModule module = new DefaultDirectSourcePreparationModule(
                observations, new DefaultImageToDiagramModule());

        DirectSourceOutcome outcome = module.prepare(command(), new RunResourceDomain(),
                (stage, completed, total) -> progressStages.add(stage),
                CancellationSignal.NEVER).toCompletableFuture().join();

        DirectSourceOutcome.Prepared prepared =
                assertInstanceOf(DirectSourceOutcome.Prepared.class, outcome);
        assertTrue(prepared.mxGraphModelXml().contains("direct-edge-a-to-b"));
        assertEquals(3, prepared.citationBindings().size());
        assertEquals(List.of("direct-node-a", "direct-node-b", "direct-edge-a-to-b"),
                prepared.citationBindings().stream().map(binding -> binding.cellId()).toList());
        assertEquals(3, prepared.evidenceAccess().items().size());
        var citationValidation = new CitationGuard(requests -> List.of()).validate(
                prepared.mxGraphModelXml(), prepared.citationBindings(),
                prepared.evidenceAccess(), true);
        assertTrue(citationValidation.accepted(), citationValidation.errors().toString());
        assertEquals(List.of("direct_visual_observation", "direct_diagram_projection"),
                progressStages);
    }

    @Test
    void preservesConfirmationBoundaryFromConversion() {
        DirectSourcePreparationModule module = new DefaultDirectSourcePreparationModule(
                (request, resources, cancellation) ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                new VisualObservationOutcome.DiagramVerified(graph(0.40))),
                new DefaultImageToDiagramModule());

        DirectSourceOutcome outcome = module.prepare(command(), new RunResourceDomain(),
                null, CancellationSignal.NEVER).toCompletableFuture().join();

        DirectSourceOutcome.NeedsConfirmation confirmation =
                assertInstanceOf(DirectSourceOutcome.NeedsConfirmation.class, outcome);
        assertEquals(List.of("LOW_CONFIDENCE_EDGE:a-to-b"), confirmation.reasons());
    }

    @Test
    void cancellationStopsBeforeReadingPixels() {
        int[] observations = {0};
        DirectSourcePreparationModule module = new DefaultDirectSourcePreparationModule(
                (request, resources, cancellation) -> {
                    observations[0]++;
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            new VisualObservationOutcome.DiagramVerified(graph(0.94)));
                },
                new DefaultImageToDiagramModule());

        DirectSourceOutcome outcome = module.prepare(command(), new RunResourceDomain(),
                null, () -> true).toCompletableFuture().join();

        assertInstanceOf(DirectSourceOutcome.Cancelled.class, outcome);
        assertEquals(0, observations[0]);
    }

    private DirectSourceCommand command() {
        return new DirectSourceCommand(new CatalogOwner(OwnerType.USER, "alice"),
                "request-1", "run-1", target, "Reconstruct the uploaded diagram");
    }

    private ObservedDiagramGraph graph(double edgeConfidence) {
        return new ObservedDiagramGraph(
                List.of(
                        new ObservedDiagramGraph.Node("a", "A", ObservedDiagramGraph.Shape.RECTANGLE,
                                new ObservationBounds(0.1, 0.2, 0.2, 0.1),
                                "", "evidence-image", 0.96),
                        new ObservedDiagramGraph.Node("b", "B", ObservedDiagramGraph.Shape.ELLIPSE,
                                new ObservationBounds(0.6, 0.2, 0.2, 0.1),
                                "", "evidence-image", 0.97)),
                List.of(new ObservedDiagramGraph.Edge("a-to-b", "a", "b", "",
                        ObservedDiagramGraph.EdgeDirection.FORWARD,
                        ObservedDiagramGraph.LineStyle.SOLID, List.of(),
                        "evidence-image", edgeConfidence)),
                List.of(), List.of());
    }
}
