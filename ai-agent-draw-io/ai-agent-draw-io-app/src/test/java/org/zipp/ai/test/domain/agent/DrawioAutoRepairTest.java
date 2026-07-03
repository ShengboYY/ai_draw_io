package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Phase 0 safety net: deterministic repair of mechanical XML mistakes plus the analyzer rules
 * that used to reject legitimate drawings (legend lines, nested-region overlaps).
 */
public class DrawioAutoRepairTest {

    private final DrawioCanvasXmlToolkit toolkit = new DrawioCanvasXmlToolkit();
    private final DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

    private static final String NODE_A =
            "<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='40' y='40' width='160' height='60' as='geometry'/></mxCell>";
    private static final String NODE_B =
            "<mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='400' y='40' width='160' height='60' as='geometry'/></mxCell>";

    @Test
    public void standaloneLegendLineIsNotABrokenEdge() {
        String xml = NODE_A
                + "<mxCell id='4' value='' style='endArrow=classic;html=1;' edge='1' parent='1'>"
                + "<mxGeometry relative='1' as='geometry'>"
                + "<mxPoint x='75' y='470' as='sourcePoint'/><mxPoint x='120' y='470' as='targetPoint'/>"
                + "</mxGeometry></mxCell>";

        CanvasAnalysis analysis = analyzer.analyze(toolkit.toGraphModel(xml), "unknown");

        assertTrue("Legend sample lines anchored by sourcePoint/targetPoint must be valid: "
                + analysis.getIssues(), analysis.isValid());
    }

    @Test
    public void edgeWithoutAnyAnchorIsStillCritical() {
        String xml = NODE_A
                + "<mxCell id='4' value='' style='endArrow=classic;html=1;' edge='1' parent='1'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>";

        CanvasAnalysis analysis = analyzer.analyze(toolkit.toGraphModel(xml), "unknown");

        assertFalse(analysis.isValid());
        assertEquals("critical", analysis.getSeverity());
    }

    @Test
    public void autoRepairRemovesEdgeWithNoResolvableEndpoints() {
        String xml = NODE_A
                + "<mxCell id='9' edge='1' parent='1' source='404' target='405'><mxGeometry relative='1' as='geometry'/></mxCell>";

        String repaired = toolkit.autoRepair(xml);

        assertFalse(repaired.contains("id=\"9\""));
        assertTrue(analyzer.analyze(repaired, "unknown").isValid());
    }

    @Test
    public void autoRepairAnchorsPartiallyDanglingEdge() {
        String xml = NODE_A + NODE_B
                + "<mxCell id='9' edge='1' parent='1' source='2' target='404'><mxGeometry relative='1' as='geometry'/></mxCell>";

        String repaired = toolkit.autoRepair(xml);

        assertTrue(repaired.contains("targetPoint"));
        assertFalse(repaired.contains("target=\"404\""));
        assertTrue(analyzer.analyze(repaired, "unknown").isValid());
    }

    @Test
    public void autoRepairDeduplicatesIds() {
        String xml = NODE_A
                + "<mxCell id='2' value='A2' vertex='1' parent='1'><mxGeometry x='400' y='200' width='160' height='60' as='geometry'/></mxCell>";

        String repaired = toolkit.autoRepair(xml);

        assertTrue(repaired.contains("id=\"2-r2\""));
        assertTrue(analyzer.analyze(repaired, "unknown").isValid());
    }

    @Test
    public void autoRepairMovesPortAttributesIntoStyle() {
        String xml = NODE_A + NODE_B
                + "<mxCell id='9' style='endArrow=classic;html=1;' edge='1' parent='1' source='2' target='3' exitX='1' exitY='0.5'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>";

        String repaired = toolkit.autoRepair(xml);

        assertFalse(repaired.contains("exitX=\"1\""));
        assertTrue(repaired.contains("exitX=1;"));
    }

    @Test
    public void autoRepairAddsMissingVertexGeometry() {
        String xml = NODE_A + "<mxCell id='5' value='NoGeo' vertex='1' parent='1'/>";

        String repaired = toolkit.autoRepair(xml);

        assertTrue(repaired.contains("id=\"5\""));
        assertTrue(analyzer.analyze(repaired, "unknown").isValid());
    }

    @Test
    public void autoRepairFlattensNestedCells() {
        String xml = "<mxCell id='2' value='Group' vertex='1' parent='1'>"
                + "<mxGeometry x='40' y='40' width='220' height='140' as='geometry'/>"
                + "<mxCell id='3' value='Child' vertex='1'><mxGeometry x='20' y='40' width='160' height='60' as='geometry'/></mxCell>"
                + "</mxCell>";

        String repaired = toolkit.autoRepair(xml);

        assertTrue(repaired.contains("parent=\"2\""));
        assertTrue(analyzer.analyze(repaired, "unknown").isValid());
    }

    @Test
    public void autoRepairPreservesOriginalTextWhenNothingToFix() {
        String clean = NODE_A + NODE_B;

        String repaired = toolkit.autoRepair(clean);

        assertEquals(toolkit.toGraphModel(clean), repaired);
    }

    @Test
    public void ancestorContainerOverlapIsNotFlagged() {
        String xml = "<mxCell id='2' value='Region' style='rounded=0;fillColor=#f5f5f5;' vertex='1' parent='1'>"
                + "<mxGeometry x='40' y='40' width='340' height='260' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='SubRegion' vertex='1' parent='2'><mxGeometry x='20' y='40' width='240' height='160' as='geometry'/></mxCell>"
                + "<mxCell id='4' value='Leaf' vertex='1' parent='3'><mxGeometry x='20' y='40' width='160' height='60' as='geometry'/></mxCell>";

        CanvasAnalysis analysis = analyzer.analyze(toolkit.toGraphModel(xml), "unknown");

        assertTrue("Grandparent/grandchild containment must not count as overlap: "
                + analysis.getIssues(), analysis.isValid());
    }

    @Test
    public void transparentBoundaryBehindNodesIsNotFlagged() {
        String xml = "<mxCell id='10' value='Boundary' style='rounded=0;whiteSpace=wrap;html=1;fillColor=none;dashed=1;' vertex='1' parent='1'>"
                + "<mxGeometry x='20' y='20' width='700' height='400' as='geometry'/></mxCell>"
                + NODE_A + NODE_B;

        CanvasAnalysis analysis = analyzer.analyze(toolkit.toGraphModel(xml), "unknown");

        assertTrue("A transparent boundary rectangle must not be reported as overlapping its content: "
                + analysis.getIssues(), analysis.isValid());
    }
}
