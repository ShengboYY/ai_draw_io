package org.zipp.ai.domain.agent.model.valobj.analysis;

public record CanvasAnalysisRequest(
        String mxGraphModelXml,
        DiagramType diagramType,
        LayoutFamily layoutHint,
        String profileVersion
) {
    public CanvasAnalysisRequest {
        diagramType = diagramType == null ? DiagramType.GENERIC : diagramType;
    }
}
