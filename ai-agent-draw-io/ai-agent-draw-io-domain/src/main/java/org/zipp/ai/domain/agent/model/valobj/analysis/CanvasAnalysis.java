package org.zipp.ai.domain.agent.model.valobj.analysis;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
@Builder
public class CanvasAnalysis {

    private boolean valid;

    private String severity;

    /**
     * How the diagram is laid out: "grid" (directional flow on rows/columns), "radial"
     * (rings / hub-and-spoke / cycles with free-routed edges), or "illustration"
     * (freeform art). Consumers relax grid-only heuristics for non-grid modes.
     */
    private String layoutMode;

    private DiagramType diagramType;

    private LayoutFamily layoutFamily;

    private String profileVersion;

    private List<CanvasAnalysisIssue> issues;

    private List<CanvasQualityIssue> qualityIssues;

    private List<CanvasCellData> cells;

    private CanvasSummaryData summary;

    public static CanvasAnalysis invalid(String severity, CanvasAnalysisIssue issue) {
        return CanvasAnalysis.builder()
                .valid(false)
                .severity(severity)
                .layoutMode("grid")
                .issues(Collections.singletonList(issue))
                .cells(Collections.emptyList())
                .summary(CanvasSummaryData.builder().nodeCount(0).edgeCount(0).summary("Canvas analysis failed.").build())
                .build();
    }

}
