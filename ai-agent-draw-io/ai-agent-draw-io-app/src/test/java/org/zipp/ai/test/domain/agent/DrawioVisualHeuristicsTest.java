package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueType;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Static visual-quality heuristics: perceptual defects that are computable from geometry
 * alone. Majors (overflow, bloated regions) drive the repair loop; minors (palette, rhythm)
 * are advisory and must never block a diagram.
 */
public class DrawioVisualHeuristicsTest {

    private final DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();
    private final DrawioCanvasXmlToolkit toolkit = new DrawioCanvasXmlToolkit();

    private CanvasAnalysis analyze(String fragments) {
        return analyzer.analyze(toolkit.toGraphModel(fragments), "unknown");
    }

    private boolean hasIssue(CanvasAnalysis analysis, CanvasIssueType type) {
        return analysis.getIssues().stream().anyMatch(issue -> issue.getType() == type);
    }

    @Test
    public void hugeLabelInTinyBoxIsAMajorOverflow() {
        String longLabel = "This label repeats a very long explanation of everything the service does ".repeat(4);
        String xml = "<mxCell id='2' value='" + longLabel + "' vertex='1' parent='1'>"
                + "<mxGeometry x='40' y='40' width='120' height='40' as='geometry'/></mxCell>";

        CanvasAnalysis analysis = analyze(xml);

        assertTrue(hasIssue(analysis, CanvasIssueType.TEXT_OVERFLOW));
        assertFalse("overflow is a blocking issue for the repair loop", analysis.isValid());
    }

    @Test
    public void normalLabelDoesNotTriggerOverflow() {
        String xml = "<mxCell id='2' value='Backend API' vertex='1' parent='1'>"
                + "<mxGeometry x='40' y='40' width='160' height='60' as='geometry'/></mxCell>";

        assertFalse(hasIssue(analyze(xml), CanvasIssueType.TEXT_OVERFLOW));
    }

    @Test
    public void regionMuchLargerThanItsContentIsFlagged() {
        String xml = "<mxCell id='2' value='Class Loading' style='rounded=1;container=1;fillColor=#dae8fc;' vertex='1' parent='1'>"
                + "<mxGeometry x='40' y='40' width='260' height='560' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='Class Loader' vertex='1' parent='2'>"
                + "<mxGeometry x='40' y='60' width='160' height='70' as='geometry'/></mxCell>";

        CanvasAnalysis analysis = analyze(xml);

        assertTrue(hasIssue(analysis, CanvasIssueType.OVERSIZED_REGION));
        assertFalse(analysis.isValid());
    }

    @Test
    public void snugRegionIsNotFlagged() {
        String xml = "<mxCell id='2' value='Region' style='rounded=1;container=1;fillColor=#dae8fc;' vertex='1' parent='1'>"
                + "<mxGeometry x='40' y='40' width='240' height='190' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='Node' vertex='1' parent='2'>"
                + "<mxGeometry x='40' y='60' width='160' height='70' as='geometry'/></mxCell>";

        assertFalse(hasIssue(analyze(xml), CanvasIssueType.OVERSIZED_REGION));
    }

    @Test
    public void lifelinesAreExemptFromRegionBloatCheck() {
        String xml = "<mxCell id='2' value='Client' style='shape=umlLifeline;perimeter=lifelinePerimeter;container=1;' vertex='1' parent='1'>"
                + "<mxGeometry x='80' y='60' width='120' height='420' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='' style='html=1;points=[];' vertex='1' parent='2'>"
                + "<mxGeometry x='55' y='80' width='10' height='100' as='geometry'/></mxCell>";

        assertFalse(hasIssue(analyze(xml), CanvasIssueType.OVERSIZED_REGION));
    }

    @Test
    public void rainbowPaletteIsAMinorAdvisoryOnly() {
        StringBuilder xml = new StringBuilder();
        String[] fills = {"#ff0000", "#00ff00", "#0000ff", "#ffff00", "#ff00ff", "#00ffff", "#fa8072", "#8a2be2"};
        for (int i = 0; i < fills.length; i++) {
            xml.append("<mxCell id='").append(i + 2).append("' value='N").append(i)
                    .append("' style='rounded=1;fillColor=").append(fills[i]).append(";' vertex='1' parent='1'>")
                    .append("<mxGeometry x='").append(40 + i * 220).append("' y='40' width='160' height='60' as='geometry'/></mxCell>");
        }

        CanvasAnalysis analysis = analyze(xml.toString());

        assertTrue(hasIssue(analysis, CanvasIssueType.PALETTE_INCOHERENT));
        assertTrue("minor style advice must not block the diagram", analysis.isValid());
    }

    @Test
    public void irregularRowGapsAreAMinorAdvisoryOnly() {
        String xml = "<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='40' y='40' width='100' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='150' y='40' width='100' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='4' value='C' vertex='1' parent='1'><mxGeometry x='560' y='40' width='100' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='5' value='D' vertex='1' parent='1'><mxGeometry x='680' y='40' width='100' height='60' as='geometry'/></mxCell>";

        CanvasAnalysis analysis = analyze(xml);

        assertTrue(hasIssue(analysis, CanvasIssueType.UNEVEN_SPACING));
        assertTrue(analysis.isValid());
    }

    @Test
    public void portedOrthogonalEdgeThroughSiblingNodeIsDetected() {
        // A -> C with side ports; B sits exactly on the straight corridor between them.
        String xml = "<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='40' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='300' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='4' value='C' vertex='1' parent='1'><mxGeometry x='560' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='5' style='edgeStyle=orthogonalEdgeStyle;exitX=1;exitY=0.5;entryX=0;entryY=0.5;' edge='1' parent='1' source='2' target='4'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>";

        CanvasAnalysis analysis = analyze(xml);

        assertTrue("simulated orthogonal path must catch the pass-through",
                hasIssue(analysis, CanvasIssueType.EDGE_NODE_CROSSING));
    }

    @Test
    public void portlessOrthogonalEdgeIsStillSkipped() {
        String xml = "<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='40' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='300' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='4' value='C' vertex='1' parent='1'><mxGeometry x='560' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='5' style='edgeStyle=orthogonalEdgeStyle;' edge='1' parent='1' source='2' target='4'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>";

        assertFalse("without ports the path is unknowable; do not guess",
                hasIssue(analyze(xml), CanvasIssueType.EDGE_NODE_CROSSING));
    }

    @Test
    public void edgeLabelSittingOnAThirdNodeIsDetected() {
        String xml = "<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='40' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='300' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='4' value='C' vertex='1' parent='1'><mxGeometry x='560' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='5' value='long midpoint label' style='endArrow=classic;' edge='1' parent='1' source='2' target='4'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>";

        assertTrue(hasIssue(analyze(xml), CanvasIssueType.EDGE_LABEL_COLLISION));
    }

    @Test
    public void overlappingLabelsOfTwoEdgesAreDetected() {
        String xml = "<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='40' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='400' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='4' value='first label' style='endArrow=classic;exitX=1;exitY=0.5;entryX=0;entryY=0.5;' edge='1' parent='1' source='2' target='3'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>"
                + "<mxCell id='5' value='second label' style='endArrow=classic;exitX=1;exitY=0.5;entryX=0;entryY=0.5;' edge='1' parent='1' source='2' target='3'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>";

        assertTrue(hasIssue(analyze(xml), CanvasIssueType.EDGE_LABEL_COLLISION));
    }

    @Test
    public void wellSeparatedLabelsAreNotFlagged() {
        String xml = "<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='40' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='400' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='4' value='C' vertex='1' parent='1'><mxGeometry x='40' y='300' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='5' value='top' style='endArrow=classic;exitX=1;exitY=0.5;entryX=0;entryY=0.5;' edge='1' parent='1' source='2' target='3'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>"
                + "<mxCell id='6' value='down' style='endArrow=classic;exitX=0.5;exitY=1;entryX=0.5;entryY=0;' edge='1' parent='1' source='2' target='4'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>";

        assertFalse(hasIssue(analyze(xml), CanvasIssueType.EDGE_LABEL_COLLISION));
    }

    @Test
    public void sidePortsOpposingTheVisualFlowAreDetected() {
        String xml = "<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='40' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='360' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='4' value='calls' style='endArrow=classic;edgeStyle=orthogonalEdgeStyle;exitX=0;exitY=0.5;entryX=1;entryY=0.5;' edge='1' parent='1' source='2' target='3'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>";

        CanvasAnalysis analysis = analyze(xml);

        assertTrue(hasIssue(analysis, CanvasIssueType.PORT_DIRECTION_MISMATCH));
        assertFalse("bad ports should trigger the repair loop", analysis.isValid());
    }

    @Test
    public void oppositeEdgesOnTheSameMiddleTrackAreDetected() {
        String xml = "<mxCell id='2' value='Frontend' vertex='1' parent='1'><mxGeometry x='40' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='Backend' vertex='1' parent='1'><mxGeometry x='360' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='4' value='request' style='endArrow=classic;edgeStyle=orthogonalEdgeStyle;exitX=1;exitY=0.5;entryX=0;entryY=0.5;' edge='1' parent='1' source='2' target='3'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>"
                + "<mxCell id='5' value='return' style='endArrow=classic;dashed=1;edgeStyle=orthogonalEdgeStyle;exitX=0;exitY=0.5;entryX=1;entryY=0.5;' edge='1' parent='1' source='3' target='2'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>";

        CanvasAnalysis analysis = analyze(xml);

        assertTrue(hasIssue(analysis, CanvasIssueType.PARALLEL_EDGE_OVERLAP));
        assertFalse("same-track request/return edges should trigger the repair loop", analysis.isValid());
    }

    @Test
    public void oppositeEdgesOnDistinctTracksAreNotFlagged() {
        String xml = "<mxCell id='2' value='Frontend' vertex='1' parent='1'><mxGeometry x='40' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='Backend' vertex='1' parent='1'><mxGeometry x='360' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='4' value='request' style='endArrow=classic;edgeStyle=orthogonalEdgeStyle;exitX=1;exitY=0.3;entryX=0;entryY=0.3;' edge='1' parent='1' source='2' target='3'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>"
                + "<mxCell id='5' value='return' style='endArrow=classic;dashed=1;edgeStyle=orthogonalEdgeStyle;exitX=0;exitY=0.7;entryX=1;entryY=0.7;' edge='1' parent='1' source='3' target='2'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>";

        assertFalse(hasIssue(analyze(xml), CanvasIssueType.PARALLEL_EDGE_OVERLAP));
    }

    @Test
    public void uniformRowIsNotFlagged() {
        String xml = "<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='40' y='40' width='100' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='260' y='40' width='100' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='4' value='C' vertex='1' parent='1'><mxGeometry x='480' y='40' width='100' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='5' value='D' vertex='1' parent='1'><mxGeometry x='700' y='40' width='100' height='60' as='geometry'/></mxCell>";

        assertFalse(hasIssue(analyze(xml), CanvasIssueType.UNEVEN_SPACING));
    }
}
