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
    public void shouldSkipLayoutHeuristicsForFreeformIllustrations() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        // A cartoon face: unlabeled overlapping ellipses, one title text, one standalone curve.
        CanvasAnalysis analysis = analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='title' value='Cute Dog' style='text;html=1;' vertex='1' parent='1'><mxGeometry x='200' y='10' width='120' height='30' as='geometry'/></mxCell>
                <mxCell id='head' style='ellipse;fillColor=#C9A26B;' vertex='1' parent='1'><mxGeometry x='100' y='60' width='300' height='240' as='geometry'/></mxCell>
                <mxCell id='earL' style='ellipse;fillColor=#7B4F28;' vertex='1' parent='1'><mxGeometry x='60' y='80' width='110' height='170' as='geometry'/></mxCell>
                <mxCell id='earR' style='ellipse;fillColor=#7B4F28;' vertex='1' parent='1'><mxGeometry x='330' y='80' width='110' height='170' as='geometry'/></mxCell>
                <mxCell id='eyeL' style='ellipse;fillColor=#FFFFFF;' vertex='1' parent='1'><mxGeometry x='170' y='130' width='50' height='50' as='geometry'/></mxCell>
                <mxCell id='eyeR' style='ellipse;fillColor=#FFFFFF;' vertex='1' parent='1'><mxGeometry x='280' y='130' width='50' height='50' as='geometry'/></mxCell>
                <mxCell id='tail' style='curved=1;endArrow=none;' edge='1' parent='1'><mxGeometry relative='1' as='geometry'><mxPoint x='430' y='250' as='sourcePoint'/><mxPoint x='520' y='180' as='targetPoint'/></mxGeometry></mxCell>
                </root></mxGraphModel>
                """, "unknown");

        assertTrue(analysis.isValid());
        assertTrue(analysis.getIssues().isEmpty());
    }

    @Test
    public void shouldTreatLightlyAnnotatedSketchAsFreeformIllustration() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        // 2 of 5 overlapping shapes carry annotation labels (40%) and nothing is connected.
        CanvasAnalysis analysis = analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='body' style='ellipse;' vertex='1' parent='1'><mxGeometry x='100' y='60' width='300' height='240' as='geometry'/></mxCell>
                <mxCell id='earL' value='Ear' style='ellipse;' vertex='1' parent='1'><mxGeometry x='60' y='80' width='110' height='170' as='geometry'/></mxCell>
                <mxCell id='earR' style='ellipse;' vertex='1' parent='1'><mxGeometry x='330' y='80' width='110' height='170' as='geometry'/></mxCell>
                <mxCell id='eyeL' value='Eye' style='ellipse;' vertex='1' parent='1'><mxGeometry x='170' y='130' width='50' height='50' as='geometry'/></mxCell>
                <mxCell id='eyeR' style='ellipse;' vertex='1' parent='1'><mxGeometry x='280' y='130' width='50' height='50' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """, "unknown");

        assertTrue(analysis.isValid());
        assertTrue(analysis.getIssues().isEmpty());
    }

    @Test
    public void shouldStillReportStructuralIssuesForFreeformIllustrations() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasAnalysis analysis = analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='head' style='ellipse;' vertex='1' parent='1'><mxGeometry x='100' y='60' width='300' height='240' as='geometry'/></mxCell>
                <mxCell id='earL' style='ellipse;' vertex='1' parent='1'><mxGeometry x='60' y='80' width='110' height='170' as='geometry'/></mxCell>
                <mxCell id='earR' style='ellipse;' vertex='1' parent='1'><mxGeometry x='330' y='80' width='110' height='170' as='geometry'/></mxCell>
                <mxCell id='nose' style='ellipse;' vertex='1' parent='1'><mxGeometry x='230' y='200' width='0' height='0' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """, "unknown");

        assertFalse(analysis.isValid());
        assertIssue(analysis.getIssues(), CanvasIssueType.MISSING_GEOMETRY, List.of("nose"));
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

    @Test
    public void shouldTreatEllipseZonesAsBackgroundsAndDetectRadialLayout() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        // Onion model: two concentric ellipse zones, a hub in the core, a ring node in the
        // outer band, and a straight port-less spoke — the canonical radial shape.
        CanvasAnalysis analysis = analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Environment' style='ellipse;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;' vertex='1' parent='1'><mxGeometry x='40' y='40' width='700' height='500' as='geometry'/></mxCell>
                <mxCell id='3' value='Core' style='ellipse;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;dashed=1;' vertex='1' parent='1'><mxGeometry x='240' y='170' width='300' height='240' as='geometry'/></mxCell>
                <mxCell id='4' value='Hub' style='rounded=1;whiteSpace=wrap;html=1;' vertex='1' parent='1'><mxGeometry x='310' y='260' width='160' height='60' as='geometry'/></mxCell>
                <mxCell id='5' value='Partner' style='rounded=1;whiteSpace=wrap;html=1;' vertex='1' parent='1'><mxGeometry x='580' y='260' width='120' height='50' as='geometry'/></mxCell>
                <mxCell id='6' value='' style='endArrow=classic;html=1;edgeStyle=none;' edge='1' parent='1' source='4' target='5'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """, "concept");

        assertEquals("radial", analysis.getLayoutMode());
        // Nodes sitting on their ring/zone backgrounds are the layout, not a defect.
        assertNoIssue(analysis.getIssues(), CanvasIssueType.NODE_OVERLAP, List.of("2", "3"));
        assertNoIssue(analysis.getIssues(), CanvasIssueType.NODE_OVERLAP, List.of("3", "4"));
        assertNoIssue(analysis.getIssues(), CanvasIssueType.NODE_OVERLAP, List.of("2", "5"));
        // The spoke exits the core zone but crosses no real node.
        assertTrue("radial onion should be a valid first draft but got: " + analysis.getIssues(),
                analysis.isValid());
    }

    @Test
    public void shouldKeepGridLayoutModeForOrthogonalDiagrams() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

        CanvasAnalysis analysis = analyzer.analyze("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='40' y='40' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='300' y='40' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='' style='edgeStyle=orthogonalEdgeStyle;html=1;exitX=1;exitY=0.5;entryX=0;entryY=0.5;' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """, "architecture");

        assertEquals("grid", analysis.getLayoutMode());
    }

    @Test
    public void shouldHandFreeRoutedEdgeIssuesBackToTheModelInsteadOfAutoRerouting() {
        DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();
        DrawioCanvasXmlToolkit toolkit = new DrawioCanvasXmlToolkit();

        String xml = """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='40' y='200' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='600' y='200' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='Blocker' vertex='1' parent='1'><mxGeometry x='330' y='200' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='5' value='' style='endArrow=classic;html=1;edgeStyle=none;' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """;

        CanvasAnalysis analysis = analyzer.analyze(xml, "concept");
        CanvasAnalysisIssue crossing = issue(analysis.getIssues(), CanvasIssueType.EDGE_NODE_CROSSING, List.of("5", "4"));
        assertEquals("free-routed edges are repaired by the model, never straightened",
                "candidate", crossing.getRepairability());

        // Neither the crossing-triggered repair nor a direct route pass may orthogonalize it.
        assertEquals("no auto_reroute issue -> repair pass leaves the input untouched",
                xml, toolkit.repairGeometryIfNeeded(xml));
        assertFalse(toolkit.routeEdges(xml).contains("orthogonalEdgeStyle"));
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
