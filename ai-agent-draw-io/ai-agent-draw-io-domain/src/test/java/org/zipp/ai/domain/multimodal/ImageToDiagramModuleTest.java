package org.zipp.ai.domain.multimodal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageToDiagramModuleTest {

    @Test
    void convertsExplicitTopologyToDeterministicEditableDrawioXml() {
        ObservedDiagramGraph graph = new ObservedDiagramGraph(
                List.of(
                        new ObservedDiagramGraph.Node("review", "Review & approve",
                                ObservedDiagramGraph.Shape.ROUNDED_RECTANGLE,
                                new ObservationBounds(0.10, 0.20, 0.20, 0.15),
                                "approval", "evidence-node-review", 0.96),
                        new ObservedDiagramGraph.Node("done", "Done",
                                ObservedDiagramGraph.Shape.ELLIPSE,
                                new ObservationBounds(0.65, 0.20, 0.15, 0.15),
                                "approval", "evidence-node-done", 0.98)),
                List.of(new ObservedDiagramGraph.Edge("approved", "review", "done",
                        "approved", ObservedDiagramGraph.EdgeDirection.FORWARD,
                        ObservedDiagramGraph.LineStyle.SOLID,
                        List.of(), "evidence-edge-approved", 0.94)),
                List.of(new ObservedDiagramGraph.Group("approval", "Approval",
                        ObservedDiagramGraph.GroupKind.GROUP,
                        new ObservationBounds(0.05, 0.10, 0.85, 0.40),
                        "evidence-group-approval", 0.93)),
                List.of());

        ImageToDiagramModule module = new DefaultImageToDiagramModule();
        ImageToDiagramOutcome first = module.convert(new ImageToDiagramCommand(graph));
        ImageToDiagramOutcome second = module.convert(new ImageToDiagramCommand(graph));

        ImageToDiagramOutcome.Converted converted =
                assertInstanceOf(ImageToDiagramOutcome.Converted.class, first);
        assertEquals(converted.mxGraphModelXml(),
                assertInstanceOf(ImageToDiagramOutcome.Converted.class, second).mxGraphModelXml());
        assertTrue(converted.mxGraphModelXml().contains("<mxGraphModel"));
        assertTrue(converted.mxGraphModelXml().contains(
                "id=\"direct-edge-approved\" edge=\"1\" parent=\"1\" source=\"direct-node-review\" target=\"direct-node-done\""));
        assertTrue(converted.mxGraphModelXml().contains("value=\"Review &amp; approve\""));
        assertTrue(converted.mxGraphModelXml().contains("parent=\"direct-group-approval\" vertex=\"1\""));
        assertEquals(List.of("direct-group-approval", "direct-node-review",
                "direct-node-done", "direct-edge-approved"), converted.cellIds());
    }

    @Test
    void lowConfidenceCriticalEdgeRequiresConfirmationAndProducesNoXml() {
        ObservedDiagramGraph graph = new ObservedDiagramGraph(
                List.of(
                        node("left", 0.10, "evidence-left"),
                        node("right", 0.60, "evidence-right")),
                List.of(new ObservedDiagramGraph.Edge("possible", "left", "right",
                        "", ObservedDiagramGraph.EdgeDirection.FORWARD,
                        ObservedDiagramGraph.LineStyle.SOLID,
                        List.of(), "evidence-edge", 0.61)),
                List.of(),
                List.of());

        ImageToDiagramOutcome outcome = new DefaultImageToDiagramModule()
                .convert(new ImageToDiagramCommand(graph));

        ImageToDiagramOutcome.NeedsConfirmation confirmation =
                assertInstanceOf(ImageToDiagramOutcome.NeedsConfirmation.class, outcome);
        assertEquals(List.of("LOW_CONFIDENCE_EDGE:possible"), confirmation.reasons());
    }

    @Test
    void rejectsDanglingEdgeInsteadOfInventingAnEndpoint() {
        ObservedDiagramGraph graph = new ObservedDiagramGraph(
                List.of(node("left", 0.10, "evidence-left")),
                List.of(new ObservedDiagramGraph.Edge("dangling", "left", "missing",
                        "", ObservedDiagramGraph.EdgeDirection.FORWARD,
                        ObservedDiagramGraph.LineStyle.SOLID,
                        List.of(), "evidence-edge", 0.99)),
                List.of(),
                List.of());

        ImageToDiagramOutcome outcome = new DefaultImageToDiagramModule()
                .convert(new ImageToDiagramCommand(graph));

        ImageToDiagramOutcome.Rejected rejected =
                assertInstanceOf(ImageToDiagramOutcome.Rejected.class, outcome);
        assertEquals(List.of("UNKNOWN_EDGE_TARGET:dangling:missing"), rejected.reasons());
    }

    @Test
    void directionlessAndLowConfidenceTextRequireConfirmation() {
        ObservedDiagramGraph graph = new ObservedDiagramGraph(
                List.of(
                        new ObservedDiagramGraph.Node("unclear", "Possible label",
                                ObservedDiagramGraph.Shape.RECTANGLE,
                                new ObservationBounds(0.1, 0.2, 0.2, 0.1),
                                "", "evidence-node", 0.40),
                        node("right", 0.60, "evidence-right")),
                List.of(new ObservedDiagramGraph.Edge("unknown-direction", "unclear", "right",
                        "", ObservedDiagramGraph.EdgeDirection.NONE,
                        ObservedDiagramGraph.LineStyle.DASHED,
                        List.of(), "evidence-edge", 0.95)),
                List.of(), List.of());

        ImageToDiagramOutcome.NeedsConfirmation outcome =
                assertInstanceOf(ImageToDiagramOutcome.NeedsConfirmation.class,
                        new DefaultImageToDiagramModule().convert(new ImageToDiagramCommand(graph)));

        assertEquals(List.of("LOW_CONFIDENCE_NODE_TEXT:unclear",
                "UNRESOLVED_EDGE_DIRECTION:unknown-direction"), outcome.reasons());
    }

    private ObservedDiagramGraph.Node node(String id, double x, String evidenceId) {
        return new ObservedDiagramGraph.Node(id, id, ObservedDiagramGraph.Shape.RECTANGLE,
                new ObservationBounds(x, 0.20, 0.20, 0.15),
                "", evidenceId, 0.95);
    }
}
