package org.zipp.ai.domain.agent.model.valobj.canvas;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
@Builder
public class DrawioCanvasSnapshot {

    private boolean valid;
    private String errorMessage;
    private String diagramType;
    private String rawXml;
    private List<CanvasNode> nodes;
    private List<CanvasEdge> edges;
    private CanvasBounds bounds;
    private String summary;

    public static DrawioCanvasSnapshot empty(String diagramType, String errorMessage) {
        return DrawioCanvasSnapshot.builder()
                .valid(false)
                .errorMessage(errorMessage)
                .diagramType(diagramType)
                .rawXml("")
                .nodes(Collections.emptyList())
                .edges(Collections.emptyList())
                .bounds(CanvasBounds.builder().x(0D).y(0D).width(0D).height(0D).build())
                .summary("No drawable Draw.io XML was found in the current context.")
                .build();
    }

    public int nodeCount() {
        return null == nodes ? 0 : nodes.size();
    }

    public int edgeCount() {
        return null == edges ? 0 : edges.size();
    }

}
