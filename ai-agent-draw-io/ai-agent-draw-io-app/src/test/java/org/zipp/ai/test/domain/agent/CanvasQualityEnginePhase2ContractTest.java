package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueType;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasQualityIssue;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CanvasQualityEnginePhase2ContractTest {

    @Test
    public void explicitEdgeCrossingHasStableIdentityAndSegmentEvidence() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasQualityIssue original = qualityIssue(analyzer.analyze(crossingEdges(false, 0), "flowchart"),
                CanvasIssueType.EDGE_EDGE_CROSSING);
        CanvasQualityIssue reordered = qualityIssue(analyzer.analyze(crossingEdges(true, 100), "flowchart"),
                CanvasIssueType.EDGE_EDGE_CROSSING);

        assertEquals(original.issueId(), reordered.issueId());
        assertEquals(1D, original.confidence(), 0.0001D);
        assertEquals("HIGH", original.evidence().attributes().get("pathConfidence"));
        assertFalse(evidenceList(original, "intersections").isEmpty());
    }

    @Test
    public void collinearEdgeOverlapIsReportedForTheSpecificEdges() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasQualityIssue issue = qualityIssue(analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='edge-a' edge='1' parent='1'><mxGeometry relative='1' as='geometry'>
                  <mxPoint x='40' y='100' as='sourcePoint'/><mxPoint x='240' y='100' as='targetPoint'/>
                </mxGeometry></mxCell>
                <mxCell id='edge-b' edge='1' parent='1'><mxGeometry relative='1' as='geometry'>
                  <mxPoint x='140' y='100' as='sourcePoint'/><mxPoint x='340' y='100' as='targetPoint'/>
                </mxGeometry></mxCell>
                </root></mxGraphModel>
                """, "flowchart"), CanvasIssueType.EDGE_COLLINEAR_OVERLAP);

        assertEquals(List.of("edge-a", "edge-b"), issue.targetCellIds().stream().sorted().toList());
        assertFalse(evidenceList(issue, "overlaps").isEmpty());
    }

    @Test
    public void edgeEvidenceUsesAbsoluteCoordinatesInsideAContainer() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasQualityIssue issue = qualityIssue(analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='container' value='Lane' style='swimlane;' vertex='1' parent='1'>
                  <mxGeometry x='100' y='200' width='400' height='240' as='geometry'/>
                </mxCell>
                <mxCell id='edge-h' edge='1' parent='container'><mxGeometry relative='1' as='geometry'>
                  <mxPoint x='20' y='80' as='sourcePoint'/><mxPoint x='220' y='80' as='targetPoint'/>
                </mxGeometry></mxCell>
                <mxCell id='edge-v' edge='1' parent='container'><mxGeometry relative='1' as='geometry'>
                  <mxPoint x='120' y='20' as='sourcePoint'/><mxPoint x='120' y='140' as='targetPoint'/>
                </mxGeometry></mxCell>
                </root></mxGraphModel>
                """, "architecture"), CanvasIssueType.EDGE_EDGE_CROSSING);

        Map<?, ?> intersection = evidenceList(issue, "intersections").get(0);
        assertEquals(220D, (Double) intersection.get("x"), 0.0001D);
        assertEquals(280D, (Double) intersection.get("y"), 0.0001D);
    }

    @Test
    public void unverifiableAutoRouteIsReportedAsALowConfidenceLimitation() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();
        String xml = """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='source' value='Source' vertex='1' parent='1'><mxGeometry x='40' y='40' width='100' height='60' as='geometry'/></mxCell>
                <mxCell id='target' value='Target' vertex='1' parent='1'><mxGeometry x='360' y='200' width='100' height='60' as='geometry'/></mxCell>
                <mxCell id='edge' style='edgeStyle=orthogonalEdgeStyle;' edge='1' parent='1' source='source' target='target'>
                  <mxGeometry relative='1' as='geometry'/>
                </mxCell>
                </root></mxGraphModel>
                """;

        for (String diagramType : List.of("flowchart", "concept", "sequence", "illustration", "unknown")) {
            CanvasQualityIssue issue = qualityIssue(analyzer.analyze(xml, diagramType),
                    CanvasIssueType.ANALYSIS_LIMITATION);
            assertTrue(diagramType, issue.confidence() < 0.5D);
            assertEquals(diagramType, "LOW", issue.evidence().attributes().get("pathConfidence"));
            assertEquals(diagramType, List.of("edge"), issue.targetCellIds().stream().toList());
        }
    }

    @Test
    public void returnLaneRulesApplyToFlowchartsButNotConceptMaps() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();
        String xml = """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='top' value='Top' vertex='1' parent='1'><mxGeometry x='140' y='40' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='middle' value='Middle' vertex='1' parent='1'><mxGeometry x='140' y='180' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='bottom' value='Bottom' vertex='1' parent='1'><mxGeometry x='140' y='320' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='return' style='edgeStyle=orthogonalEdgeStyle;' edge='1' parent='1' source='bottom' target='top'>
                  <mxGeometry relative='1' as='geometry'><Array as='points'>
                    <mxPoint x='200' y='280'/><mxPoint x='200' y='140'/>
                  </Array></mxGeometry>
                </mxCell>
                </root></mxGraphModel>
                """;

        CanvasAnalysis flowchart = analyzer.analyze(xml, "flowchart");
        CanvasAnalysis concept = analyzer.analyze(xml, "concept");

        assertTrue(hasIssue(flowchart, CanvasIssueType.RETURN_GUTTER_VIOLATION));
        assertTrue(hasIssue(flowchart, CanvasIssueType.PROTECTED_LANE_INTRUSION));
        assertFalse(hasIssue(concept, CanvasIssueType.RETURN_GUTTER_VIOLATION));
        assertFalse(hasIssue(concept, CanvasIssueType.PROTECTED_LANE_INTRUSION));
    }

    @Test
    public void reverseEdgeWithoutAnExplicitReturnRouteHasDirectionEvidence() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasQualityIssue issue = qualityIssue(analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='top' value='Top' vertex='1' parent='1'><mxGeometry x='140' y='40' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='bottom' value='Bottom' vertex='1' parent='1'><mxGeometry x='140' y='260' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='reverse' style='edgeStyle=none;' edge='1' parent='1' source='bottom' target='top'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """, "flowchart"), CanvasIssueType.EDGE_DIRECTION_MISMATCH);

        assertEquals("MEDIUM", issue.evidence().attributes().get("pathConfidence"));
        assertEquals(List.of("reverse"), issue.targetCellIds().stream().toList());
    }

    @Test
    public void edgeWithMultipleGeometricConflictsIsMarkedAmbiguous() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasQualityIssue issue = qualityIssue(analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='main' edge='1' parent='1'><mxGeometry relative='1' as='geometry'>
                  <mxPoint x='40' y='100' as='sourcePoint'/><mxPoint x='360' y='100' as='targetPoint'/>
                </mxGeometry></mxCell>
                <mxCell id='left-cross' edge='1' parent='1'><mxGeometry relative='1' as='geometry'>
                  <mxPoint x='140' y='20' as='sourcePoint'/><mxPoint x='140' y='180' as='targetPoint'/>
                </mxGeometry></mxCell>
                <mxCell id='right-cross' edge='1' parent='1'><mxGeometry relative='1' as='geometry'>
                  <mxPoint x='260' y='20' as='sourcePoint'/><mxPoint x='260' y='180' as='targetPoint'/>
                </mxGeometry></mxCell>
                </root></mxGraphModel>
                """, "flowchart"), CanvasIssueType.AMBIGUOUS_EDGE_TRACE);

        assertTrue(issue.targetCellIds().contains("main"));
        assertEquals(3, issue.targetCellIds().size());
        assertEquals(2, issue.evidence().attributes().get("conflictCount"));
    }

    private String crossingEdges(boolean reverseOrder, int offset) {
        String horizontal = """
                <mxCell id='edge-h' style='edgeStyle=none;' edge='1' parent='1'><mxGeometry relative='1' as='geometry'>
                  <mxPoint x='%d' y='%d' as='sourcePoint'/><mxPoint x='%d' y='%d' as='targetPoint'/>
                  <Array as='points'><mxPoint x='%d' y='%d'/></Array>
                </mxGeometry></mxCell>
                """.formatted(40 + offset, 100 + offset, 360 + offset, 100 + offset,
                200 + offset, 100 + offset);
        String vertical = """
                <mxCell id='edge-v' edge='1' parent='1'><mxGeometry relative='1' as='geometry'>
                  <mxPoint x='%d' y='%d' as='sourcePoint'/><mxPoint x='%d' y='%d' as='targetPoint'/>
                </mxGeometry></mxCell>
                """.formatted(200 + offset, 20 + offset, 200 + offset, 180 + offset);
        return "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>"
                + (reverseOrder ? vertical + horizontal : horizontal + vertical)
                + "</root></mxGraphModel>";
    }

    private CanvasQualityIssue qualityIssue(CanvasAnalysis analysis, CanvasIssueType type) {
        return analysis.getQualityIssues().stream()
                .filter(issue -> issue.type() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing issue " + type + ": " + analysis.getQualityIssues()));
    }

    @SuppressWarnings("unchecked")
    private List<Map<?, ?>> evidenceList(CanvasQualityIssue issue, String key) {
        return (List<Map<?, ?>>) issue.evidence().attributes().get(key);
    }

    private boolean hasIssue(CanvasAnalysis analysis, CanvasIssueType type) {
        return analysis.getQualityIssues().stream().anyMatch(issue -> issue.type() == type);
    }
}
