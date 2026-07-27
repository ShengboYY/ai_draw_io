package org.zipp.ai.domain.multimodal;

import java.util.List;
import java.util.Objects;

public record ImageToDiagramCommand(ObservedDiagramGraph graph,
                                    List<DirectClarification> clarifications) {
    public ImageToDiagramCommand {
        graph = Objects.requireNonNull(graph, "graph");
        clarifications = List.copyOf(clarifications == null ? List.of() : clarifications);
    }

    public ImageToDiagramCommand(ObservedDiagramGraph graph) {
        this(graph, List.of());
    }
}
