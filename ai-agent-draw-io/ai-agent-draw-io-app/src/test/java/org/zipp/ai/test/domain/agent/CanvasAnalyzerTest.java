package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueType;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class CanvasAnalyzerTest {

    @Test
    public void shouldReportTypedStructuralIssuesWithTargets() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasAnalysis analysis = analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='2' value='Duplicate API' vertex='1' parent='1'><mxGeometry x='260' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='No size' vertex='1' parent='1'><mxGeometry x='420' y='100' width='0' height='0' as='geometry'/></mxCell>
                <mxCell id='4' value='Broken' edge='1' parent='1' source='2' target='404'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """, "architecture");

        assertFalse(analysis.isValid());
        assertEquals("critical", analysis.getSeverity());
        assertIssue(analysis.getIssues(), CanvasIssueType.DUP_ID, List.of("2"));
        assertIssue(analysis.getIssues(), CanvasIssueType.MISSING_GEOMETRY, List.of("3"));
        assertIssue(analysis.getIssues(), CanvasIssueType.BROKEN_EDGE, List.of("4", "404"));
        assertEquals(3, analysis.getSummary().getNodeCount());
        assertEquals(1, analysis.getSummary().getEdgeCount());
    }

    @Test
    public void shouldDetectOverlapsUsingAbsoluteChildCoordinates() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasAnalysis analysis = analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Container' style='swimlane;html=1;' vertex='1' parent='1'><mxGeometry x='100' y='100' width='300' height='220' as='geometry'/></mxCell>
                <mxCell id='3' value='Child' vertex='1' parent='2'><mxGeometry x='40' y='30' width='110' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='Peer' vertex='1' parent='1'><mxGeometry x='130' y='120' width='120' height='70' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """, "architecture");

        assertFalse(analysis.isValid());
        assertIssue(analysis.getIssues(), CanvasIssueType.NODE_OVERLAP, List.of("3", "4"));
    }

    @Test
    public void shouldDetectEdgeNodeCrossing() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasAnalysis analysis = analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Source' vertex='1' parent='1'><mxGeometry x='40' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Target' vertex='1' parent='1'><mxGeometry x='360' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='Blocker' vertex='1' parent='1'><mxGeometry x='210' y='110' width='80' height='80' as='geometry'/></mxCell>
                <mxCell id='5' value='' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """, "architecture");

        assertFalse(analysis.isValid());
        assertIssue(analysis.getIssues(), CanvasIssueType.EDGE_NODE_CROSSING, List.of("5", "4"));
        assertEquals("auto_reroute", issue(analysis.getIssues(), CanvasIssueType.EDGE_NODE_CROSSING, List.of("5", "4")).getRepairability());
    }

    @Test
    public void shouldDetectEdgeNodeCrossingUsingAbsoluteChildCoordinates() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasAnalysis analysis = analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='10' value='Container' style='swimlane;html=1;' vertex='1' parent='1'><mxGeometry x='100' y='100' width='400' height='250' as='geometry'/></mxCell>
                <mxCell id='11' value='Source' vertex='1' parent='10'><mxGeometry x='30' y='80' width='70' height='40' as='geometry'/></mxCell>
                <mxCell id='12' value='Target' vertex='1' parent='10'><mxGeometry x='300' y='80' width='70' height='40' as='geometry'/></mxCell>
                <mxCell id='13' value='Blocker' vertex='1' parent='10'><mxGeometry x='160' y='60' width='80' height='80' as='geometry'/></mxCell>
                <mxCell id='14' value='' edge='1' parent='10' source='11' target='12'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """, "architecture");

        assertFalse(analysis.isValid());
        assertIssue(analysis.getIssues(), CanvasIssueType.EDGE_NODE_CROSSING, List.of("14", "13"));
    }

    @Test
    public void shouldDetectEdgeNodeCrossingWhenWaypointIsInsideNode() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasAnalysis analysis = analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Source' vertex='1' parent='1'><mxGeometry x='40' y='40' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Target' vertex='1' parent='1'><mxGeometry x='360' y='40' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='Blocker' vertex='1' parent='1'><mxGeometry x='180' y='160' width='80' height='80' as='geometry'/></mxCell>
                <mxCell id='5' value='' edge='1' parent='1' source='2' target='3'>
                    <mxGeometry relative='1' as='geometry'><Array as='points'><mxPoint x='220' y='200'/><mxPoint x='320' y='200'/></Array></mxGeometry>
                </mxCell>
                </root></mxGraphModel>
                """, "architecture");

        assertIssue(analysis.getIssues(), CanvasIssueType.EDGE_NODE_CROSSING, List.of("5", "4"));
    }

    @Test
    public void repairGeometryIfNeededReroutesOnlyWhenAnEdgeCrossesANode() {
        DrawioCanvasXmlToolkit toolkit = new DrawioCanvasXmlToolkit();

        String crossing = """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Source' vertex='1' parent='1'><mxGeometry x='40' y='40' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Target' vertex='1' parent='1'><mxGeometry x='360' y='40' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='Blocker' vertex='1' parent='1'><mxGeometry x='180' y='160' width='80' height='80' as='geometry'/></mxCell>
                <mxCell id='5' value='' edge='1' parent='1' source='2' target='3'>
                    <mxGeometry relative='1' as='geometry'><Array as='points'><mxPoint x='220' y='200'/><mxPoint x='320' y='200'/></Array></mxGeometry>
                </mxCell>
                </root></mxGraphModel>
                """;
        String clean = """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Source' vertex='1' parent='1'><mxGeometry x='40' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Target' vertex='1' parent='1'><mxGeometry x='360' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='5' value='' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """;

        assertNotEquals("a crossing patch should be rerouted", crossing, toolkit.repairGeometryIfNeeded(crossing));
        assertEquals("a clean patch should pass through untouched", clean, toolkit.repairGeometryIfNeeded(clean));
    }

    @Test
    public void shouldFlagRemovableWaypointsWhenDirectRouteIsClear() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasAnalysis analysis = analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Source' vertex='1' parent='1'><mxGeometry x='40' y='40' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Target' vertex='1' parent='1'><mxGeometry x='360' y='40' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='5' value='' edge='1' parent='1' source='2' target='3'>
                    <mxGeometry relative='1' as='geometry'><Array as='points'><mxPoint x='200' y='160'/></Array></mxGeometry>
                </mxCell>
                </root></mxGraphModel>
                """, "architecture");

        assertIssue(analysis.getIssues(), CanvasIssueType.REMOVABLE_WAYPOINT, List.of("5"));
        assertTrue("an aesthetic-only nit should not invalidate the canvas", analysis.isValid());
    }

    @Test
    public void shouldNotFlagRemovableWaypointsWhenTheBendAvoidsANode() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasAnalysis analysis = analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Source' vertex='1' parent='1'><mxGeometry x='40' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Target' vertex='1' parent='1'><mxGeometry x='360' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='Blocker' vertex='1' parent='1'><mxGeometry x='210' y='110' width='80' height='80' as='geometry'/></mxCell>
                <mxCell id='5' value='' edge='1' parent='1' source='2' target='3'>
                    <mxGeometry relative='1' as='geometry'><Array as='points'><mxPoint x='160' y='60'/><mxPoint x='320' y='60'/></Array></mxGeometry>
                </mxCell>
                </root></mxGraphModel>
                """, "architecture");

        assertNoIssue(analysis.getIssues(), CanvasIssueType.REMOVABLE_WAYPOINT, List.of("5"));
    }

    @Test
    public void shouldNotFlagRemovableWaypointsForParallelEdges() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasAnalysis analysis = analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='40' y='40' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='360' y='40' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='5' value='' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'><Array as='points'><mxPoint x='200' y='30'/></Array></mxGeometry></mxCell>
                <mxCell id='6' value='' edge='1' parent='1' source='3' target='2'><mxGeometry relative='1' as='geometry'><Array as='points'><mxPoint x='200' y='110'/></Array></mxGeometry></mxCell>
                </root></mxGraphModel>
                """, "architecture");

        assertNoIssue(analysis.getIssues(), CanvasIssueType.REMOVABLE_WAYPOINT, List.of("5"));
        assertNoIssue(analysis.getIssues(), CanvasIssueType.REMOVABLE_WAYPOINT, List.of("6"));
    }

    @Test
    public void shouldNotReportEdgeNodeCrossingWhenWaypointsRouteAroundNode() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasAnalysis analysis = analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Source' vertex='1' parent='1'><mxGeometry x='40' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Target' vertex='1' parent='1'><mxGeometry x='360' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='Blocker' vertex='1' parent='1'><mxGeometry x='210' y='110' width='80' height='80' as='geometry'/></mxCell>
                <mxCell id='5' value='' edge='1' parent='1' source='2' target='3'>
                    <mxGeometry relative='1' as='geometry'><Array as='points'><mxPoint x='160' y='60'/><mxPoint x='320' y='60'/></Array></mxGeometry>
                </mxCell>
                </root></mxGraphModel>
                """, "architecture");

        assertNoIssue(analysis.getIssues(), CanvasIssueType.EDGE_NODE_CROSSING, List.of("5", "4"));
    }

    private void assertIssue(List<CanvasAnalysisIssue> issues, CanvasIssueType type, List<String> targetCellIds) {
        assertTrue("Expected issue " + type + " with targets " + targetCellIds,
                issues.stream().anyMatch(issue ->
                        type == issue.getType() && issue.getTargetCellIds().equals(targetCellIds)));
    }

    private CanvasAnalysisIssue issue(List<CanvasAnalysisIssue> issues, CanvasIssueType type, List<String> targetCellIds) {
        return issues.stream()
                .filter(issue -> type == issue.getType() && issue.getTargetCellIds().equals(targetCellIds))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected issue " + type + " with targets " + targetCellIds));
    }

    private void assertNoIssue(List<CanvasAnalysisIssue> issues, CanvasIssueType type, List<String> targetCellIds) {
        assertFalse("Did not expect issue " + type + " with targets " + targetCellIds,
                issues.stream().anyMatch(issue ->
                        type == issue.getType() && issue.getTargetCellIds().equals(targetCellIds)));
    }
}
