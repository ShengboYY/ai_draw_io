package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DrawioStructuralValidationTest {

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
