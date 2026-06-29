package org.zipp.ai.domain.agent.model.valobj.quality;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DiagramQualityReport {

    private int nodeCount;

    private int edgeCount;

    private String diagramType;

    private String canvasSummary;

    private String overallRisk;

    private List<QualityIssue> layoutIssues;

    private List<QualityIssue> readabilityIssues;

    private List<QualityIssue> edgeIssues;

    private List<QualityIssue> semanticHints;

    private List<String> recommendations;

}
