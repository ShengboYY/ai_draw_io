package org.zipp.ai.domain.multimodal;

import java.util.Objects;

public record ImageToDiagramCommand(ObservedDiagramGraph graph) {
    public ImageToDiagramCommand {
        graph = Objects.requireNonNull(graph, "graph");
    }
}
